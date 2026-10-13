import { mount, flushPromises } from '@vue/test-utils'
import { setActivePinia, createPinia } from 'pinia'
import { createMemoryHistory } from 'vue-router'
import App from '@/App.vue'
import MdmListView from '@/views/mdm/MdmListView.vue'
import { createAppRouter } from '@/router'
import { useAuthStore } from '@/stores/auth'
import { pageMdm } from '@/api/mdm'

vi.mock('@/api/mdm', () => ({ pageMdm: vi.fn(), getMdm: vi.fn(), createMdm: vi.fn(), updateMdm: vi.fn(), deleteMdm: vi.fn() }))

const settle = async () => { await flushPromises(); await new Promise((r) => setTimeout(r, 30)); await flushPromises() }
const resp = (total = 26) => ({ records: Array.from({ length: Math.min(total, 20) }, (_, i) => ({ id: i + 1, code: `M${i}`, name: 'n', status: 1 })), total })

describe('列表页首次加载只请求一次', () => {
  beforeEach(() => { pageMdm.mockReset(); pageMdm.mockImplementation(() => Promise.resolve(resp())) })

  it('直接挂载组件：只发 1 次 page=1&size=20', async () => {
    setActivePinia(createPinia())
    mount(MdmListView, { props: { resource: 'materials' } })
    await settle()
    expect(pageMdm).toHaveBeenCalledTimes(1)
    expect(pageMdm).toHaveBeenCalledWith('materials', { page: 1, size: 20 })
  })

  it.each([0, 26])('经路由打开 /mdm/materials（total=%i）：只发 1 次', async (total) => {
    pageMdm.mockImplementation(() => Promise.resolve(resp(total)))
    const pinia = createPinia(); setActivePinia(pinia)
    const auth = useAuthStore(); auth.setToken('t'); auth.setUser({ username: 'a', authorities: ['mdm:write'] })
    const router = createAppRouter(createMemoryHistory())
    await router.push('/mdm/materials'); await router.isReady()
    mount(App, { global: { plugins: [pinia, router] } })
    await settle()
    expect(pageMdm).toHaveBeenCalledTimes(1)
    await router.push('/mdm/customers'); await settle()
    expect(pageMdm).toHaveBeenCalledTimes(2)
    expect(pageMdm).toHaveBeenLastCalledWith('customers', { page: 1, size: 20 })
  })

  it('相同参数的并发 load 合并为一次请求', async () => {
    let resolve
    pageMdm.mockImplementation(() => new Promise((r) => { resolve = r }))
    setActivePinia(createPinia())
    const w = mount(MdmListView, { props: { resource: 'materials' } })
    w.vm.load(); w.vm.load()
    expect(pageMdm).toHaveBeenCalledTimes(1)
    resolve(resp()); await settle()
    expect(w.vm.total).toBe(26)
    w.vm.load(); await settle()
    expect(pageMdm).toHaveBeenCalledTimes(2)
  })

  it('参数变化时旧请求结果被丢弃（成功与失败）', async () => {
    const pending = []
    pageMdm.mockImplementation(() => new Promise((res, rej) => pending.push({ res, rej })))
    setActivePinia(createPinia())
    const w = mount(MdmListView, { props: { resource: 'materials' } })
    w.vm.onPageChange(2)
    w.vm.onPageChange(3)
    expect(pageMdm).toHaveBeenCalledTimes(3)
    pending[0].res(resp(99)); pending[1].rej(new Error('x')); await settle()
    expect(w.vm.total).toBe(0)
    pending[2].res(resp(5)); await settle()
    expect(w.vm.total).toBe(5)
  })
})
