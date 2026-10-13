import { mount, flushPromises } from '@vue/test-utils'
import { setActivePinia, createPinia } from 'pinia'
import { ElMessage } from 'element-plus'
import LoginView from '@/views/LoginView.vue'
import { useAuthStore } from '@/stores/auth'
import * as authApi from '@/api/auth'

const replace = vi.fn()
const route = { query: {} }
vi.mock('vue-router', () => ({ useRouter: () => ({ replace }), useRoute: () => route }))
vi.mock('@/api/auth', () => ({ login: vi.fn(), me: vi.fn() }))

describe('LoginView', () => {
  let pinia
  beforeEach(() => {
    pinia = createPinia(); setActivePinia(pinia)
    replace.mockReset(); route.query = {}
    vi.spyOn(ElMessage, 'success').mockImplementation(() => {})
    vi.spyOn(ElMessage, 'warning').mockImplementation(() => {})
  })
  const mk = () => mount(LoginView, { global: { plugins: [pinia] } })

  it('渲染中文表单', () => {
    const w = mk()
    expect(w.text()).toContain('ERP 管理系统')
    expect(w.find('[data-test=submit]').text()).toContain('登 录')
  })
  it('空表单提示且不调用接口', async () => {
    const w = mk()
    await w.vm.onSubmit()
    expect(ElMessage.warning).toHaveBeenCalled()
    expect(authApi.login).not.toHaveBeenCalled()
  })
  it('登录成功保存 token、用户并跳转 redirect', async () => {
    authApi.login.mockResolvedValue({ token: 'jwt' })
    authApi.me.mockResolvedValue({ username: 'admin', authorities: ['mdm:write'] })
    route.query = { redirect: '/sales' }
    const w = mk()
    await w.find('input[data-test=username]').setValue('admin')
    await w.find('input[data-test=password]').setValue('admin123')
    await w.find('[data-test=submit]').trigger('click')
    await flushPromises()
    expect(authApi.login).toHaveBeenCalledWith({ username: 'admin', password: 'admin123' })
    const s = useAuthStore()
    expect(s.token).toBe('jwt')
    expect(s.username).toBe('admin')
    expect(replace).toHaveBeenCalledWith('/sales')
  })
  it('me 失败时回退用户名，默认跳首页', async () => {
    authApi.login.mockResolvedValue({ token: 'jwt' })
    authApi.me.mockRejectedValue(new Error('x'))
    const w = mk()
    w.vm.form.username = 'bob'; w.vm.form.password = 'p'
    await w.vm.onSubmit()
    expect(useAuthStore().username).toBe('bob')
    expect(replace).toHaveBeenCalledWith('/')
  })
  it('外部 redirect 被忽略，跳首页', async () => {
    authApi.login.mockResolvedValue({ token: 'jwt' })
    authApi.me.mockResolvedValue({ username: 'a', authorities: [] })
    route.query = { redirect: '//evil.com' }
    const w = mk()
    w.vm.form.username = 'a'; w.vm.form.password = 'b'
    await w.vm.onSubmit()
    expect(replace).toHaveBeenCalledWith('/')
  })
  it('登录失败不保存 token 不跳转', async () => {
    authApi.login.mockRejectedValue(new Error('用户名或密码错误'))
    const w = mk()
    w.vm.form.username = 'a'; w.vm.form.password = 'b'
    await w.vm.onSubmit()
    expect(useAuthStore().token).toBe('')
    expect(replace).not.toHaveBeenCalled()
  })
})
