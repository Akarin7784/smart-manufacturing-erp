import { mount, flushPromises } from '@vue/test-utils'
import { setActivePinia, createPinia } from 'pinia'
import { ElMessage, ElMessageBox } from 'element-plus'
import axios from 'axios'
import MdmListView from '@/views/mdm/MdmListView.vue'
import MdmFormDialog from '@/views/mdm/MdmFormDialog.vue'
import { pageMdm, updateMdm, deleteMdm } from '@/api/mdm'
import { useAuthStore } from '@/stores/auth'
import { BizError, onRejected, CANCELED } from '@/utils/request'
import { ErrorCode } from '@/api/errorCodes'
import pkg from '../package.json'
import { readFileSync } from 'node:fs'

vi.mock('@/api/mdm', () => ({ pageMdm: vi.fn(), getMdm: vi.fn(), createMdm: vi.fn(), updateMdm: vi.fn(), deleteMdm: vi.fn() }))

const settle = async () => { await flushPromises(); await new Promise((r) => setTimeout(r, 0)); await flushPromises() }
const resp = (total, code = 'X') => ({ records: [{ id: 1, code, name: 'n', status: 1 }], total })
const reject = (err) => { const p = Promise.reject(err); p.catch(() => {}); return p }

/** 可控的 pageMdm：记录每次调用的 signal 与 resolve/reject */
function deferredPage() {
  const calls = []
  pageMdm.mockImplementation((resource, params, config) => new Promise((res, rej) => {
    calls.push({ resource, params, signal: config.signal, res, rej })
    config.signal.addEventListener('abort', () => rej(Object.assign(new Error('canceled'), { canceled: true })))
  }))
  return calls
}

function mountList(resource = 'materials') {
  const pinia = createPinia(); setActivePinia(pinia)
  useAuthStore().setUser({ username: 'u', authorities: ['mdm:write'] })
  return mount(MdmListView, { props: { resource }, global: { plugins: [pinia] }, attachTo: document.body })
}

beforeEach(() => {
  vi.clearAllMocks()
  document.body.innerHTML = ''
  vi.spyOn(ElMessage, 'success').mockImplementation(() => {})
  vi.spyOn(ElMessage, 'error').mockImplementation(() => {})
})

describe('1) 去重 / force / AbortController', () => {
  it('同参数并发复用；force 绕过去重并 abort 旧请求', async () => {
    const calls = deferredPage()
    const w = mountList(); await settle()
    w.vm.load()
    expect(calls).toHaveLength(1)
    w.vm.refresh()
    expect(calls).toHaveLength(2)
    expect(calls[0].signal.aborted).toBe(true)
    expect(calls[1].signal.aborted).toBe(false)
    calls[1].res(resp(7)); await settle()
    expect(w.vm.total).toBe(7)
  })

  it('保存成功后刷新不复用进行中的旧请求', async () => {
    const calls = deferredPage()
    const w = mountList(); await settle()
    w.findComponent(MdmFormDialog).vm.$emit('saved'); await settle()
    expect(calls).toHaveLength(2)
    expect(calls[0].signal.aborted).toBe(true)
    calls[1].res(resp(3, 'NEW')); await settle()
    expect(w.text()).toContain('NEW')
  })

  it('删除成功后强制刷新（即使有同参数请求在途）', async () => {
    const calls = deferredPage()
    const w = mountList(); await settle()
    calls[0].res(resp(5)); await settle()
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm')
    deleteMdm.mockResolvedValue(null)
    w.vm.load() // 制造一个同参数在途请求
    const p = w.vm.onDelete({ id: 1, code: 'X', name: 'n' }); await settle()
    expect(calls).toHaveLength(3)
    expect(calls[1].signal.aborted).toBe(true)
    calls[2].res(resp(4)); await p; await settle()
    expect(w.vm.total).toBe(4)
  })

  it('A→B→A 快速切换：只采用最后一个 A 的结果，loading 不被提前清掉', async () => {
    const calls = deferredPage()
    const w = mountList(); await settle() // A1
    w.vm.onPageChange(2) // B
    w.vm.onPageChange(1) // A2（与 A1 同 key）
    expect(calls).toHaveLength(3)
    expect(calls[0].signal.aborted && calls[1].signal.aborted).toBe(true)
    // 旧 A1 即使还返回了结果，也不能覆盖或清掉 A2
    calls[0].res(resp(111)); await settle()
    expect(w.vm.total).toBe(0)
    expect(w.find('.el-loading-mask').exists()).toBe(true)
    // A2 在途时再次 load 同参数仍去重
    w.vm.load()
    expect(calls).toHaveLength(3)
    calls[2].res(resp(9)); await settle()
    expect(w.vm.total).toBe(9)
  })

  it('卸载时取消在途请求', async () => {
    const calls = deferredPage()
    const w = mountList(); await settle()
    w.unmount()
    expect(calls[0].signal.aborted).toBe(true)
  })

  it('真实 axios 取消：拦截器静默拒绝，不弹提示', async () => {
    const err = await onRejected(new axios.CanceledError('canceled')).catch((e) => e)
    expect(err).toMatchObject({ code: CANCELED, canceled: true })
    expect(ElMessage.error).not.toHaveBeenCalled()
  })
})

describe('2) 40900 自动刷新', () => {
  it('编辑保存 40900：关闭弹窗、emit stale', async () => {
    const pinia = createPinia(); setActivePinia(pinia)
    updateMdm.mockImplementation(() => reject(new BizError(ErrorCode.CONCURRENT_MODIFIED, '数据已被修改，请刷新重试', 409)))
    const d = mount(MdmFormDialog, { props: { modelValue: true, resource: 'customers', row: { id: 2, code: 'C1', name: 'c', status: 1 } }, global: { plugins: [pinia] }, attachTo: document.body })
    await settle()
    await d.vm.onSave(); await settle()
    expect(d.emitted('stale')).toHaveLength(1)
    expect(d.emitted('update:modelValue').at(-1)).toEqual([false])
    expect(d.emitted('saved')).toBeFalsy()
  })
  it('其他错误不 emit stale，弹窗保持', async () => {
    const pinia = createPinia(); setActivePinia(pinia)
    updateMdm.mockImplementation(() => reject(new BizError(400, 'bad', 400)))
    const d = mount(MdmFormDialog, { props: { modelValue: true, resource: 'customers', row: { id: 2, code: 'C1', name: 'c', status: 1 } }, global: { plugins: [pinia] }, attachTo: document.body })
    await settle(); await d.vm.onSave(); await settle()
    expect(d.emitted('stale')).toBeFalsy()
    expect(d.emitted('update:modelValue')).toBeFalsy()
  })
  it('列表收到 stale 后强制刷新', async () => {
    pageMdm.mockResolvedValue(resp(1))
    const w = mountList(); await settle()
    pageMdm.mockClear()
    w.findComponent(MdmFormDialog).vm.$emit('stale'); await settle()
    expect(pageMdm).toHaveBeenCalledTimes(1)
  })
  it('删除 40900：自动刷新；删除 40901：不刷新', async () => {
    pageMdm.mockResolvedValue(resp(1))
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm')
    const w = mountList(); await settle()
    pageMdm.mockClear()
    deleteMdm.mockImplementation(() => reject(new BizError(40900, 'x', 409)))
    await w.vm.onDelete({ id: 1, code: 'X', name: 'n' }); await settle()
    expect(pageMdm).toHaveBeenCalledTimes(1)
    deleteMdm.mockImplementation(() => reject(new BizError(40901, 'x', 409)))
    await w.vm.onDelete({ id: 1, code: 'X', name: 'n' }); await settle()
    expect(pageMdm).toHaveBeenCalledTimes(1)
  })
})

describe('3)/4) 杂项', () => {
  it('MdmFormDialog 无重复注释', () => {
    const src = readFileSync('src/views/mdm/MdmFormDialog.vue', 'utf8')
    expect(src).not.toMatch(/保留弹窗便于修改重试，保留弹窗便于修改重试/)
  })
  it('engines.node 与 CI setup-node 版本、.nvmrc 一致', () => {
    const ci = readFileSync('../.github/workflows/ci.yml', 'utf8')
    const versions = [...ci.matchAll(/node-version:\s*'?(\d+)'?/g)].map((m) => m[1])
    expect(versions.length).toBeGreaterThan(0)
    expect(new Set(versions).size).toBe(1)
    expect(pkg.engines.node).toBe(`^${versions[0]}`)
    expect(readFileSync('.nvmrc', 'utf8').trim()).toBe(versions[0])
  })
})
