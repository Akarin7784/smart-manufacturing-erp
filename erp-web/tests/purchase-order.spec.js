import { mount, flushPromises } from '@vue/test-utils'
import { setActivePinia, createPinia } from 'pinia'
import { ElMessage, ElMessageBox } from 'element-plus'
import PurchaseOrderDialog from '@/views/purchase/PurchaseOrderDialog.vue'
import PurchaseOrderListView from '@/views/purchase/PurchaseOrderListView.vue'
import { createPurchaseOrder, updatePurchaseOrder, getPurchaseOrder, pagePurchaseOrders, submitPurchaseOrder, receivePurchaseOrder } from '@/api/purchase'
import { listApprovalTodo, decideApproval } from '@/api/workflow'
import { pageMdm } from '@/api/mdm'
import { useAuthStore } from '@/stores/auth'
import { BizError } from '@/utils/request'
import { ErrorCode, ERROR_MESSAGES } from '@/api/errorCodes'
import { PO_MSG } from '@/config/purchase'

vi.mock('@/api/purchase', () => ({
  pagePurchaseOrders: vi.fn(), getPurchaseOrder: vi.fn(), createPurchaseOrder: vi.fn(), updatePurchaseOrder: vi.fn(),
  submitPurchaseOrder: vi.fn(), receivePurchaseOrder: vi.fn()
}))
vi.mock('@/api/workflow', () => ({ listApprovalTodo: vi.fn(), listApprovals: vi.fn(), decideApproval: vi.fn() }))
vi.mock('@/api/mdm', () => ({ pageMdm: vi.fn() }))

const failWith = (code, msg = 'x') => () => { const p = Promise.reject(new BizError(code, msg)); p.catch(() => {}); return p }
const KEY = 'Idempotency-Key'

function setup(username = 'alice', authorities = ['purchase:write', 'inventory:write']) {
  const pinia = createPinia(); setActivePinia(pinia)
  useAuthStore().setUser({ username, authorities })
  return pinia
}
const draft = (over = {}) => ({ order: { id: 5, orderNo: 'PO1', supplierId: 1, warehouseId: 2, status: 'DRAFT', remark: 'r', totalAmount: '8.33', version: 3, createdBy: 'alice', ...over }, items: [{ materialId: 9, qty: '2.5', price: '3.333', amount: '8.33' }] })

const mountDialog = (props = {}) => mount(PurchaseOrderDialog, { props: { modelValue: true, retryDelay: 1, ...props }, global: { plugins: [setup()] }, attachTo: document.body })
function fillNew(w) {
  Object.assign(w.vm.form, { supplierId: 1, warehouseId: 2, remark: '' })
  w.vm.form.items.splice(0, w.vm.form.items.length, { materialId: 9, qty: '2.5', price: '3.333' })
}

beforeEach(() => {
  vi.clearAllMocks()
  for (const k of ['success', 'error', 'warning']) vi.spyOn(ElMessage, k).mockImplementation(() => {})
  document.body.innerHTML = ''
})

describe('弹窗：表单与请求体', () => {
  it('合计/行金额用 decimal 计算并按展示规则格式化', async () => {
    const w = mountDialog(); fillNew(w)
    w.vm.form.items.push({ materialId: 9, qty: '1000000', price: '1234.5' })
    await flushPromises()
    expect(w.vm.lineAmounts).toEqual(['8.33', '1234500000.00'])
    expect(w.vm.total).toBe('1234500008.33')
    expect(document.querySelector('[data-testid=po-total]').textContent).toBe('1,234,500,008.33')
    expect([...document.querySelectorAll('[data-testid=po-amount]')].map((e) => e.textContent)).toEqual(['8.33', '1,234,500,000.00'])
  })
  it('新建：数量/单价以字符串提交，带 Idempotency-Key，不带 version', async () => {
    createPurchaseOrder.mockResolvedValue(draft())
    const w = mountDialog(); fillNew(w)
    const key = w.vm.idemKey
    expect(key).toMatch(/^[0-9a-f-]{36}$/)
    await w.vm.onSave(); await flushPromises()
    const [body, cfg] = createPurchaseOrder.mock.calls[0]
    expect(body).toEqual({ supplierId: 1, warehouseId: 2, remark: null, items: [{ materialId: 9, qty: '2.5', price: '3.333' }] })
    expect(cfg.headers[KEY]).toBe(key)
    expect(w.emitted('saved')).toBeTruthy()
    expect(w.emitted('update:modelValue').at(-1)).toEqual([false])
    expect(w.vm.idemKey).toBeNull()
  })
  it('编辑：PUT 原样带 version，不带幂等键', async () => {
    updatePurchaseOrder.mockResolvedValue(draft({ version: 4 }))
    const w = mountDialog({ detail: draft() }); await flushPromises()
    expect(w.vm.idemKey).toBeNull()
    w.vm.form.items[0].qty = '10.0000'
    await w.vm.onSave(); await flushPromises()
    const [id, body, cfg] = updatePurchaseOrder.mock.calls[0]
    expect(id).toBe(5)
    expect(body).toEqual({ version: 3, supplierId: 1, warehouseId: 2, remark: 'r', items: [{ materialId: 9, qty: '10.0000', price: '3.333' }] })
    expect(cfg.headers).toBeUndefined()
  })
  it('前端校验失败不发请求', async () => {
    const w = mountDialog(); fillNew(w)
    w.vm.form.items[0].price = '1.23456'
    await w.vm.onSave()
    expect(createPurchaseOrder).not.toHaveBeenCalled()
    expect(w.vm.errorText).toBe('第 1 行单价最多 4 位小数')
    w.vm.form.items[0].price = '1'; w.vm.form.supplierId = null
    await w.vm.onSave()
    expect(w.vm.errorText).toBe('请选择供应商')
  })
  it('行金额超出 16 位整数被前端拦截', async () => {
    const w = mountDialog(); fillNew(w)
    w.vm.form.items[0].qty = '99999999999999'; w.vm.form.items[0].price = '99999999999999'
    await w.vm.onSave()
    expect(w.vm.errorText).toBe('第 1 行金额整数部分最多 16 位')
    expect(createPurchaseOrder).not.toHaveBeenCalled()
  })
  it('关闭弹窗丢弃幂等键，重新打开生成新键', async () => {
    const w = mountDialog(); const k1 = w.vm.idemKey
    await w.setProps({ modelValue: false }); expect(w.vm.idemKey).toBeNull()
    await w.setProps({ modelValue: true }); expect(w.vm.idemKey).not.toBe(k1)
  })
})

describe('弹窗：错误码处理', () => {
  it('40903：关闭弹窗并通知刷新该单', async () => {
    updatePurchaseOrder.mockImplementation(failWith(ErrorCode.STATUS_CHANGED))
    const w = mountDialog({ detail: draft() })
    await w.vm.onSave(); await flushPromises()
    expect(w.emitted('update:modelValue').at(-1)).toEqual([false])
    expect(w.emitted('stale')[0]).toEqual([5])
    expect(ElMessage.error).toHaveBeenCalledWith(ERROR_MESSAGES[40903])
  })
  it('40900：提示并重新 GET，仍是草稿则用新 version 刷新表单、弹窗保持', async () => {
    updatePurchaseOrder.mockImplementation(failWith(ErrorCode.CONCURRENT_MODIFIED))
    getPurchaseOrder.mockResolvedValue(draft({ version: 7, remark: '别人改的' }))
    const w = mountDialog({ detail: draft() })
    await w.vm.onSave(); await flushPromises()
    expect(getPurchaseOrder).toHaveBeenCalledWith(5, { silent: true })
    expect(ElMessage.error).toHaveBeenCalledWith('数据已被修改，请刷新后重试')
    expect(w.vm.errorText).toBe('数据已被修改，请刷新后重试')
    expect(w.vm.payload().version).toBe(7)
    expect(w.vm.form.remark).toBe('别人改的')
    expect(w.emitted('stale')).toBeFalsy()
  })
  it('40900 后重新 GET 发现已非草稿：按 40903 处理', async () => {
    updatePurchaseOrder.mockImplementation(failWith(ErrorCode.CONCURRENT_MODIFIED))
    getPurchaseOrder.mockResolvedValue(draft({ status: 'PENDING', version: 4 }))
    const w = mountDialog({ detail: draft() })
    await w.vm.onSave(); await flushPromises()
    expect(w.emitted('update:modelValue').at(-1)).toEqual([false])
    expect(w.emitted('stale')).toBeTruthy()
  })
  it('普通 400：弹窗保持并显示后端 msg', async () => {
    createPurchaseOrder.mockImplementation(failWith(400, '供应商不存在: 1'))
    const w = mountDialog(); fillNew(w)
    await w.vm.onSave(); await flushPromises()
    expect(w.vm.errorText).toBe('供应商不存在: 1')
    expect(w.emitted('update:modelValue')).toBeFalsy()
    expect(w.vm.idemKey).not.toBeNull()
  })
  it('40902：显示 msg，弹窗保持', async () => {
    createPurchaseOrder.mockImplementation(failWith(ErrorCode.DUPLICATE_REQUEST, ERROR_MESSAGES[40902]))
    const w = mountDialog(); fillNew(w)
    await w.vm.onSave(); await flushPromises()
    expect(w.vm.errorText).toBe(ERROR_MESSAGES[40902])
    expect(w.emitted('update:modelValue')).toBeFalsy()
  })
  it('50301：弹窗保持、提示取号繁忙；重试复用同一 key，成功后丢弃', async () => {
    createPurchaseOrder.mockImplementationOnce(failWith(ErrorCode.DOC_NO_BUSY)).mockResolvedValueOnce(draft())
    const w = mountDialog(); fillNew(w)
    const key = w.vm.idemKey
    await w.vm.onSave(); await flushPromises()
    expect(w.vm.errorText).toBe(PO_MSG.docNoBusy)
    expect(ElMessage.error).toHaveBeenCalledWith('取号繁忙，请稍后重试')
    expect(w.emitted('update:modelValue')).toBeFalsy()
    await w.vm.onSave(); await flushPromises()
    expect(createPurchaseOrder.mock.calls.map((c) => c[1].headers[KEY])).toEqual([key, key])
    expect(w.emitted('saved')).toBeTruthy()
    expect(w.vm.idemKey).toBeNull()
  })
  it('40904：按钮显示处理中，约 1s 后用同一 key 自动重试，重试成功即关闭', async () => {
    let release
    createPurchaseOrder.mockImplementationOnce(failWith(ErrorCode.REQUEST_IN_PROGRESS)).mockImplementationOnce(() => new Promise((r) => { release = r }))
    const w = mountDialog({ retryDelay: 1000 }); fillNew(w)
    vi.useFakeTimers()
    const p = w.vm.onSave()
    await vi.advanceTimersByTimeAsync(0)
    expect(w.vm.inProgress).toBe(true)
    expect(document.querySelector('[data-testid=po-save]').textContent).toContain('处理中')
    expect(createPurchaseOrder).toHaveBeenCalledTimes(1)
    await vi.advanceTimersByTimeAsync(999)
    expect(createPurchaseOrder).toHaveBeenCalledTimes(1)
    await vi.advanceTimersByTimeAsync(1)
    expect(createPurchaseOrder).toHaveBeenCalledTimes(2)
    release(draft()); await p
    vi.useRealTimers()
    const keys = createPurchaseOrder.mock.calls.map((c) => c[1].headers[KEY])
    expect(new Set(keys).size).toBe(1)
    expect(w.emitted('saved')).toBeTruthy()
  })
  it('40904 自动重试 3 次仍 40904：提示可能已提交，弹窗保持，key 不变', async () => {
    createPurchaseOrder.mockImplementation(failWith(ErrorCode.REQUEST_IN_PROGRESS))
    const w = mountDialog(); fillNew(w)
    const key = w.vm.idemKey
    await w.vm.onSave(); await flushPromises()
    expect(createPurchaseOrder).toHaveBeenCalledTimes(4)
    expect(createPurchaseOrder.mock.calls.every((c) => c[1].headers[KEY] === key)).toBe(true)
    expect(w.vm.errorText).toBe('可能已提交，请先刷新列表确认')
    expect(w.emitted('update:modelValue')).toBeFalsy()
    expect(w.vm.idemKey).toBe(key)
    expect(w.vm.saving).toBe(false)
  })
})

describe('列表页', () => {
  const rows = [
    { id: 1, orderNo: 'PO-A', status: 'DRAFT', totalAmount: '1234567890123456.12', version: 0, createdBy: 'alice' },
    { id: 2, orderNo: 'PO-B', status: 'PENDING', totalAmount: '10', version: 1, createdBy: 'bob' },
    { id: 3, orderNo: 'PO-C', status: 'PENDING', totalAmount: '10', version: 1, createdBy: 'alice' },
    { id: 4, orderNo: 'PO-D', status: 'APPROVED', totalAmount: '10', version: 2, createdBy: 'bob' },
    { id: 6, orderNo: 'PO-E', status: 'PENDING', totalAmount: '10', version: 1, createdBy: 'carol' }
  ]
  const todo = [
    { id: 20, bizType: 'PURCHASE_ORDER', bizId: 2, applicant: 'bob' },
    { id: 30, bizType: 'PURCHASE_ORDER', bizId: 3, applicant: 'alice' },
    { id: 99, bizType: 'SALES_ORDER', bizId: 6, applicant: 'carol' }
  ]
  async function mountList(user, auths) {
    pagePurchaseOrders.mockResolvedValue({ records: rows.map((r) => ({ ...r })), total: rows.length })
    listApprovalTodo.mockResolvedValue(todo)
    pageMdm.mockResolvedValue({ records: [] })
    const w = mount(PurchaseOrderListView, { global: { plugins: [setup(user, auths)] }, attachTo: document.body })
    await flushPromises()
    return w
  }

  it('行与金额 testid、千分位展示', async () => {
    const w = await mountList()
    expect(w.find('[data-testid=po-row-1]').exists()).toBe(true)
    expect(w.find('[data-testid=po-amount]').text()).toBe('1,234,567,890,123,456.12')
  })
  it('审批按钮只对当前节点审批人显示，且不显示在自己的单据上', async () => {
    const w = await mountList('alice')
    expect(w.vm.canDecide(w.vm.records[1])).toBe(true) // bob 的单，在我的待办里
    expect(w.vm.canDecide(w.vm.records[2])).toBe(false) // 自己的单（即使出现在待办里）
    expect(w.vm.canDecide(w.vm.records[4])).toBe(false) // 不在采购待办里
    expect(w.vm.canDecide(w.vm.records[0])).toBe(false) // 草稿
    expect(w.findAll('[data-testid=po-approve]')).toHaveLength(1)
    expect(w.findAll('[data-testid=po-reject]')).toHaveLength(1)
  })
  it('提交后按 id 重新 GET 刷新行（version 变化）', async () => {
    submitPurchaseOrder.mockResolvedValue({ approvalId: 7 })
    getPurchaseOrder.mockResolvedValue({ order: { ...rows[0], status: 'PENDING', version: 1 }, items: [] })
    const w = await mountList()
    await w.vm.onSubmit(w.vm.records[0]); await flushPromises()
    expect(submitPurchaseOrder).toHaveBeenCalledWith(1)
    expect(getPurchaseOrder).toHaveBeenCalledWith(1, { silent: true })
    expect(w.vm.records[0]).toMatchObject({ status: 'PENDING', version: 1 })
  })
  it('审批通过：用待办实例 id 调 decide，然后重新 GET', async () => {
    decideApproval.mockResolvedValue({})
    getPurchaseOrder.mockResolvedValue({ order: { ...rows[1], status: 'APPROVED', version: 2 }, items: [] })
    const w = await mountList('alice')
    await w.vm.onDecide(w.vm.records[1], true); await flushPromises()
    expect(decideApproval).toHaveBeenCalledWith(20, { approved: true, comment: '' })
    expect(w.vm.records[1]).toMatchObject({ status: 'APPROVED', version: 2 })
  })
  it('驳回：带意见，单据回到草稿', async () => {
    vi.spyOn(ElMessageBox, 'prompt').mockResolvedValue({ value: '价格太高' })
    decideApproval.mockResolvedValue({})
    getPurchaseOrder.mockResolvedValue({ order: { ...rows[1], status: 'DRAFT', version: 2 }, items: [] })
    const w = await mountList('alice')
    await w.vm.onDecide(w.vm.records[1], false); await flushPromises()
    expect(decideApproval).toHaveBeenCalledWith(20, { approved: false, comment: '价格太高' })
    expect(w.vm.records[1].status).toBe('DRAFT')
  })
  it('收货：带幂等键；50301 保留 key 重试复用，成功后丢弃', async () => {
    receivePurchaseOrder.mockImplementationOnce(failWith(ErrorCode.DOC_NO_BUSY)).mockResolvedValueOnce({})
    getPurchaseOrder.mockResolvedValue({ order: { ...rows[3] }, items: [] })
    const w = await mountList()
    await w.vm.onReceive(w.vm.records[3]); await flushPromises()
    expect(ElMessage.error).toHaveBeenCalledWith('取号繁忙，请稍后重试')
    const k = w.vm.receiveKeys[4]
    expect(k).toBeTruthy()
    await w.vm.onReceive(w.vm.records[3]); await flushPromises()
    expect(receivePurchaseOrder.mock.calls.map((c) => c[1].headers[KEY])).toEqual([k, k])
    expect(w.vm.receiveKeys[4]).toBeUndefined()
  })
  it('编辑前 GET 发现已非草稿：不打开弹窗', async () => {
    getPurchaseOrder.mockResolvedValue({ order: { ...rows[0], status: 'PENDING' }, items: [] })
    const w = await mountList()
    await w.vm.openEdit(w.vm.records[0]); await flushPromises()
    expect(w.vm.dialogVisible).toBe(false)
  })
  it('无写权限不显示新建/提交', async () => {
    const w = await mountList('viewer', [])
    expect(w.find('[data-test=create]').exists()).toBe(false)
    expect(w.find('[data-testid=po-submit]').exists()).toBe(false)
    expect(w.find('[data-testid=po-receive]').exists()).toBe(false)
  })
})
