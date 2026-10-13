import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import MdmListView from '@/views/mdm/MdmListView.vue'
import { pageMdm } from '@/api/mdm'
import { MDM_RESOURCES } from '@/config/mdm'

const SIG = { signal: expect.any(AbortSignal) }
vi.mock('@/api/mdm', () => ({ pageMdm: vi.fn(), getMdm: vi.fn(), deleteMdm: vi.fn() }))

const page = (records, total) => ({ records, total, size: 20, current: 1, pages: 1 })

describe('MdmListView', () => {
  beforeEach(() => { pageMdm.mockReset(); setActivePinia(createPinia()) })

  it('挂载即按默认分页加载并渲染表格', async () => {
    pageMdm.mockResolvedValue(page([{ id: 1, code: 'M001', name: '螺丝', spec: 'M6', unit: '个', category: '五金', status: 1 }, { id: 2, code: 'M002', name: '停用料', status: 0, createdAt: '2026-10-14T03:24:49.088012' }, { id: 3, code: 'M003', name: '未知', status: null }], 3))
    const w = mount(MdmListView, { props: { resource: 'materials' } })
    await flushPromises()
    expect(pageMdm).toHaveBeenCalledWith('materials', { page: 1, size: 20 }, SIG)
    expect(w.text()).toContain('物料编码')
    expect(w.text()).toContain('M001')
    expect(w.text()).toContain('启用')
    expect(w.text()).toContain('停用')
    expect(w.vm.total).toBe(3)
    const rows = w.findAll('.el-table__body tr')
    expect(rows[0].text()).toContain('启用')
    expect(rows[1].text()).toContain('停用')
    expect(rows[1].text()).toContain('2026-10-14 03:24:49')
    expect(rows[2].text()).not.toMatch(/启用|停用/)
  })
  it('搜索带 keyword 并回到第 1 页；重置清空', async () => {
    pageMdm.mockResolvedValue(page([], 0))
    const w = mount(MdmListView, { props: { resource: 'customers' } })
    await flushPromises()
    w.vm.onPageChange(3); await flushPromises()
    expect(pageMdm).toHaveBeenLastCalledWith('customers', { page: 3, size: 20 }, SIG)
    expect(w.findComponent({ name: 'ElTableColumn' }).props('index')(0)).toBe(41)
    await w.find('input[data-test=keyword]').setValue('  华东 ')
    await w.find('[data-test=search]').trigger('click'); await flushPromises()
    expect(pageMdm).toHaveBeenLastCalledWith('customers', { page: 1, size: 20, keyword: '华东' }, SIG)
    await w.find('[data-test=reset]').trigger('click'); await flushPromises()
    expect(pageMdm).toHaveBeenLastCalledWith('customers', { page: 1, size: 20 }, SIG)
  })
  it('切换每页条数', async () => {
    pageMdm.mockResolvedValue(page([], 0))
    const w = mount(MdmListView, { props: { resource: 'suppliers' } })
    await flushPromises()
    w.vm.onPageChange(2); w.vm.onSizeChange(50); await flushPromises()
    expect(pageMdm).toHaveBeenLastCalledWith('suppliers', { page: 1, size: 50 }, SIG)
  })
  it('接口失败（后端返回 code≠0）清空表格', async () => {
    const actual = await vi.importActual('@/api/mdm')
    const { default: request } = await import('@/utils/request')
    const { ElMessage } = await import('element-plus')
    const errSpy = vi.spyOn(ElMessage, 'error').mockImplementation(() => {})
    request.defaults.adapter = async (config) => ({ data: { code: 500, msg: '系统异常' }, status: 200, headers: {}, config })
    pageMdm.mockImplementation(actual.pageMdm)
    const w = mount(MdmListView, { props: { resource: 'warehouses' }, global: { plugins: [createPinia()] } })
    await flushPromises()
    expect(w.vm.records).toEqual([])
    expect(w.vm.total).toBe(0)
    expect(w.text()).toContain('暂无数据')
    expect(errSpy).toHaveBeenCalledWith('系统异常，请稍后重试')
    delete request.defaults.adapter
  })
  it('切换 resource 重新加载', async () => {
    pageMdm.mockResolvedValue(page([], 0))
    const w = mount(MdmListView, { props: { resource: 'materials' } })
    await flushPromises()
    await w.setProps({ resource: 'warehouses' }); await flushPromises()
    expect(pageMdm).toHaveBeenLastCalledWith('warehouses', { page: 1, size: 20 }, SIG)
    expect(w.text()).toContain('仓库编码')
  })
  it('四类主数据配置与后端实体字段一致', () => {
    expect(Object.keys(MDM_RESOURCES)).toEqual(['materials', 'customers', 'suppliers', 'warehouses'])
    expect(MDM_RESOURCES.materials.columns.map((c) => c.prop)).toEqual(['code', 'name', 'spec', 'unit', 'category', 'status', 'createdAt'])
    expect(MDM_RESOURCES.warehouses.columns.map((c) => c.prop)).toContain('address')
  })
})
