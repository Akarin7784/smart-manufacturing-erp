import { setActivePinia, createPinia } from 'pinia'
import { useAuthStore, TOKEN_KEY, USER_KEY } from '@/stores/auth'

describe('auth store', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('默认未登录', () => {
    const s = useAuthStore()
    expect(s.isLoggedIn).toBe(false)
    expect(s.username).toBe('')
    expect(s.authorities).toEqual([])
  })
  it('setToken 写入/清除 localStorage', () => {
    const s = useAuthStore()
    s.setToken('abc')
    expect(s.isLoggedIn).toBe(true)
    expect(localStorage.getItem(TOKEN_KEY)).toBe('abc')
    s.setToken('')
    expect(localStorage.getItem(TOKEN_KEY)).toBeNull()
  })
  it('从 localStorage 恢复状态', () => {
    localStorage.setItem(TOKEN_KEY, 't1')
    localStorage.setItem(USER_KEY, JSON.stringify({ username: 'admin', authorities: ['mdm:write'] }))
    const s = useAuthStore()
    expect(s.token).toBe('t1')
    expect(s.username).toBe('admin')
    expect(s.hasAuthority('mdm:write')).toBe(true)
    expect(s.hasAuthority('x')).toBe(false)
  })
  it('损坏的用户 JSON 不报错', () => {
    localStorage.setItem(USER_KEY, '{bad')
    expect(useAuthStore().user).toBeNull()
  })
  it('logout 清空 token 与用户', () => {
    const s = useAuthStore()
    s.setToken('t'); s.setUser({ username: 'u' })
    s.logout()
    expect(s.isLoggedIn).toBe(false)
    expect(s.user).toBeNull()
    expect(localStorage.getItem(USER_KEY)).toBeNull()
  })
})
