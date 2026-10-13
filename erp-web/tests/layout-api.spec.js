import { mount, flushPromises } from '@vue/test-utils'
import { setActivePinia, createPinia } from 'pinia'
import { createMemoryHistory } from 'vue-router'
import { ElMessage } from 'element-plus'
import App from '@/App.vue'
import { createAppRouter } from '@/router'
import { useAuthStore } from '@/stores/auth'
import request from '@/utils/request'
import { login, me } from '@/api/auth'
import { pageMdm, getMdm } from '@/api/mdm'

describe('MainLayout + 占位页', () => {
  it('显示用户名、菜单，退出登录回到登录页', async () => {
    vi.spyOn(ElMessage, 'success').mockImplementation(() => {})
    const pinia = createPinia(); setActivePinia(pinia)
    const auth = useAuthStore(); auth.setToken('t'); auth.setUser({ username: 'admin' })
    const router = createAppRouter(createMemoryHistory())
    await router.push('/finance'); await router.isReady()
    const w = mount(App, { global: { plugins: [pinia, router] } })
    await flushPromises()
    expect(w.text()).toContain('admin')
    for (const t of ['主数据', '采购', '销售', '库存', '财务']) expect(w.text()).toContain(t)
    expect(w.text()).toContain('财务管理页面开发中')
    await w.find('[data-test=logout]').trigger('click'); await flushPromises()
    expect(auth.isLoggedIn).toBe(false)
    await vi.waitFor(() => expect(router.currentRoute.value.name).toBe('login'))
  })
})

describe('api 封装', () => {
  it('调用正确的后端路径', async () => {
    const spyPost = vi.spyOn(request, 'post').mockResolvedValue({})
    const spyGet = vi.spyOn(request, 'get').mockResolvedValue({})
    await login({ username: 'a', password: 'b' })
    await me()
    await pageMdm('materials', { page: 1, size: 20 })
    await getMdm('customers', 5)
    expect(spyPost).toHaveBeenCalledWith('/api/auth/login', { username: 'a', password: 'b' })
    expect(spyGet).toHaveBeenCalledWith('/api/auth/me')
    expect(spyGet).toHaveBeenCalledWith('/api/mdm/materials', { params: { page: 1, size: 20 } })
    expect(spyGet).toHaveBeenCalledWith('/api/mdm/customers/5')
  })
})
