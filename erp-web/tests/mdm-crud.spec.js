import { mount, flushPromises } from '@vue/test-utils'
import { setActivePinia, createPinia } from 'pinia'
import { ElMessage, ElMessageBox } from 'element-plus'
import MdmListView from '@/views/mdm/MdmListView.vue'
import MdmFormDialog from '@/views/mdm/MdmFormDialog.vue'
import { pageMdm, createMdm, updateMdm, deleteMdm } from '@/api/mdm'
import { useAuthStore } from '@/stores/auth'
import { MDM_FORMS } from '@/config/mdm'
import { BizError } from '@/utils/request'
import { ErrorCode, ERROR_MESSAGES } from '@/api/errorCodes'

const SIG = { signal: expect.any(AbortSignal) }
vi.mock('@/api/mdm', () => ({ pageMdm: vi.fn(), getMdm: vi.fn(), createMdm: vi.fn(), updateMdm: vi.fn(), deleteMdm: vi.fn() }))

const rows = [{ id: 7, code: 'M007', name: '螺丝', spec: 'M6', unit: '个', category: '五金', status: 1 }]
const ok = () => Promise.resolve({})
const failWith = (err) => () => { const p = Promise.reject(err); p.catch(() => {}); return p }
const fail = () => { const p = Promise.reject(new Error('x')); p.catch(() => {}); return p }

function setup(authorities = ['mdm:write']) {
  const pinia = createPinia(); setActivePinia(pinia)
  useAuthStore().setUser({ username: 'u', authorities })
  return pinia
}
const mountList = (resource = 'materials', authorities) => mount(MdmListView, { props: { resource }, global: { plugins: [setup(authorities)] }, attachTo: document.body })
const mountForm = (props) => mount(MdmFormDialog, { props: { modelValue: true, resource: 'materials', ...props }, global: { plugins: [setup()] }, attachTo: document.body })

beforeEach(() => {
  vi.clearAllMocks()
  pageMdm.mockResolvedValue({ records: rows, total: 1 })
  vi.spyOn(ElMessage, 'success').mockImplementation(() => {})
  vi.spyOn(ElMessage, 'error').mockImplementation(() => {})
  document.body.innerHTML = ''
})

describe('写权限控制', () => {
  it('有 mdm:write 显示新增/编辑/删除', async () => {
    const w = mountList(); await flushPromises()
    expect(w.vm.canWrite).toBe(true)
    expect(w.find('[data-test=create]').exists()).toBe(true)
    expect(w.text()).toContain('编辑')
    expect(w.text()).toContain('删除')
  })
  it('无 mdm:write 隐藏写按钮', async () => {
    const w = mountList('customers', []); await flushPromises()
    expect(w.vm.canWrite).toBe(false)
    expect(w.find('[data-test=create]').exists()).toBe(false)
    expect(w.text()).not.toContain('操作')
  })
})

describe('列表页新增/编辑/删除', () => {
  it('新增与编辑打开弹窗', async () => {
    const w = mountList(); await flushPromises()
    await w.find('[data-test=create]').trigger('click')
    expect(w.vm.formVisible).toBe(true)
    expect(w.vm.editingRow).toBeNull()
    w.vm.openForm(rows[0])
    expect(w.vm.editingRow).toEqual(rows[0])
  })
  it('确认删除后调用接口并刷新', async () => {
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm')
    deleteMdm.mockImplementation(ok)
    const w = mountList(); await flushPromises()
    pageMdm.mockClear()
    await w.vm.onDelete(rows[0]); await flushPromises()
    expect(ElMessageBox.confirm.mock.calls[0][0]).toContain('螺丝')
    expect(deleteMdm).toHaveBeenCalledWith('materials', 7)
    expect(pageMdm).toHaveBeenCalledTimes(1)
  })
  it('删除最后一条且非首页时回退一页', async () => {
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm')
    deleteMdm.mockImplementation(ok)
    const w = mountList(); await flushPromises()
    w.vm.onPageChange(2); await flushPromises()
    await w.vm.onDelete(rows[0]); await flushPromises()
    expect(pageMdm).toHaveBeenLastCalledWith('materials', { page: 1, size: 20 }, SIG)
  })
  it('取消删除不调用接口', async () => {
    vi.spyOn(ElMessageBox, 'confirm').mockImplementation(fail)
    const w = mountList(); await flushPromises()
    await w.vm.onDelete(rows[0])
    expect(deleteMdm).not.toHaveBeenCalled()
  })
  it('删除失败（如 403）不刷新', async () => {
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm')
    deleteMdm.mockImplementation(fail)
    const w = mountList(); await flushPromises()
    pageMdm.mockClear()
    await w.vm.onDelete(rows[0]); await flushPromises()
    expect(pageMdm).not.toHaveBeenCalled()
    expect(ElMessage.success).not.toHaveBeenCalled()
  })
  it('弹窗保存成功后刷新列表', async () => {
    const w = mountList(); await flushPromises()
    pageMdm.mockClear()
    w.findComponent(MdmFormDialog).vm.$emit('saved'); await flushPromises()
    expect(pageMdm).toHaveBeenCalledTimes(1)
  })
})

describe('业务错误（页面不再重复提示）', () => {
  it('删除 40901：页面不额外弹错误，不刷新', async () => {
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm')
    deleteMdm.mockImplementation(failWith(new BizError(ErrorCode.REFERENCED, ERROR_MESSAGES[40901], 409)))
    const w = mountList(); await flushPromises()
    pageMdm.mockClear()
    await w.vm.onDelete(rows[0]); await flushPromises()
    expect(ElMessage.error).not.toHaveBeenCalled()
    expect(pageMdm).not.toHaveBeenCalled()
  })
  it('新增 40001：弹窗保持、页面不额外弹错误', async () => {
    createMdm.mockImplementation(failWith(new BizError(ErrorCode.DUPLICATE_CODE, ERROR_MESSAGES[40001], 400)))
    const w = mountForm(); await flushPromises()
    Object.assign(w.vm.form, { code: 'M1', name: 'n' })
    await w.vm.onSave(); await flushPromises()
    expect(ElMessage.error).not.toHaveBeenCalled()
    expect(w.emitted('saved')).toBeFalsy()
  })
})

describe('编辑 PUT 携带原编码（四类主数据）', () => {
  it.each([
    ['materials', { id: 1, code: 'M001', name: '物料', spec: 's', unit: 'u', category: 'c', status: 1, version: 3 }],
    ['customers', { id: 2, code: 'C001', name: '客户', contact: '张三', phone: '138', status: 1, version: 0 }],
    ['suppliers', { id: 3, code: 'S001', name: '供应商', contact: '李四', phone: '139', status: 1, version: 5 }],
    ['warehouses', { id: 4, code: 'W001', name: '仓库', address: '浦东', status: 1, version: 1 }]
  ])('%s：code 原样提交，即使表单值被篡改', async (resource, row) => {
    updateMdm.mockImplementation(ok)
    const w = mount(MdmFormDialog, { props: { modelValue: true, resource, row }, global: { plugins: [setup()] }, attachTo: document.body })
    await flushPromises()
    w.vm.form.name = row.name + '-改'
    w.vm.form.status = 0
    w.vm.form.code = 'HACKED'
    await w.vm.onSave(); await flushPromises()
    const [r, id, body] = updateMdm.mock.calls[0]
    expect([r, id]).toEqual([resource, row.id])
    expect(body.code).toBe(row.code)
    expect(body.name).toBe(row.name + '-改')
    expect(body.status).toBe(0)
    expect(body.version).toBe(row.version)
  })
})

describe('MdmFormDialog', () => {
  it('编码提示：新增说明不可重复（含已删除），编辑说明不可修改', async () => {
    mountForm(); await flushPromises()
    expect(document.querySelector('[data-test=hint-code]').textContent).toContain('含已删除')
    document.body.innerHTML = ''
    mountForm({ row: rows[0] }); await flushPromises()
    expect(document.querySelector('[data-test=hint-code]').textContent).toContain('不可修改')
  })
  it('新增：标题、默认启用、必填校验阻止提交', async () => {
    const w = mountForm(); await flushPromises()
    expect(document.body.textContent).toContain('新增物料')
    expect(w.vm.form.status).toBe(1)
    await w.vm.onSave(); await flushPromises()
    expect(createMdm).not.toHaveBeenCalled()
  })
  it('超长字段校验失败', async () => {
    const w = mountForm(); await flushPromises()
    Object.assign(w.vm.form, { code: 'x'.repeat(65), name: 'n' })
    await w.vm.onSave(); await flushPromises()
    expect(createMdm).not.toHaveBeenCalled()
  })
  it('新增提交：去空格、空串转 null、关闭并 emit saved', async () => {
    createMdm.mockImplementation(ok)
    const w = mountForm(); await flushPromises()
    Object.assign(w.vm.form, { code: ' M100 ', name: '垫片', spec: '', status: 0 })
    await w.vm.onSave(); await flushPromises()
    expect(createMdm).toHaveBeenCalledWith('materials', { code: 'M100', name: '垫片', spec: null, unit: null, category: null, status: 0 })
    expect(createMdm.mock.calls.at(-1)[1]).not.toHaveProperty('version')
    expect(w.emitted('update:modelValue').at(-1)).toEqual([false])
    expect(w.emitted('saved')).toBeTruthy()
  })
  it('编辑：回填、编码禁用且不提交、调用 PUT', async () => {
    updateMdm.mockImplementation(ok)
    const w = mountForm({ row: rows[0] }); await flushPromises()
    expect(document.body.textContent).toContain('编辑物料')
    expect(w.vm.form.name).toBe('螺丝')
    expect(document.querySelector('input[data-test=field-code]').disabled).toBe(true)
    w.vm.form.name = '新螺丝'
    await w.vm.onSave(); await flushPromises()
    expect(updateMdm).toHaveBeenCalledWith('materials', 7, { code: 'M007', name: '新螺丝', spec: 'M6', unit: '个', category: '五金', status: 1 })
  })
  it('保存失败（409/编码已存在）保持弹窗', async () => {
    createMdm.mockImplementation(fail)
    const w = mountForm(); await flushPromises()
    Object.assign(w.vm.form, { code: 'M1', name: 'n' })
    await w.vm.onSave(); await flushPromises()
    expect(w.emitted('saved')).toBeFalsy()
    expect(w.emitted('update:modelValue')).toBeFalsy()
  })
  it('取消按钮关闭', async () => {
    const w = mountForm(); await flushPromises()
    document.querySelector('[data-test=cancel]').click()
    expect(w.emitted('update:modelValue')[0]).toEqual([false])
  })
  it.each(Object.keys(MDM_FORMS))('%s 表单字段配置合法', (r) => {
    const f = MDM_FORMS[r]
    expect(f.slice(0, 2).map((x) => [x.prop, x.required])).toEqual([['code', true], ['name', true]])
    expect(f.every((x) => x.max > 0)).toBe(true)
  })
})
