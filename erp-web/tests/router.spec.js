import { setActivePinia, createPinia } from 'pinia'
import { createMemoryHistory } from 'vue-router'
import { createAppRouter, authGuard, routes } from '@/router'
import { useAuthStore } from '@/stores/auth'

describe('路由守卫', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('未登录访问受保护页跳登录并带 redirect', () => {
    expect(authGuard({ meta: {}, fullPath: '/mdm/customers' })).toEqual({ name: 'login', query: { redirect: '/mdm/customers' } })
  })
  it('已登录放行', () => {
    useAuthStore().setToken('t')
    expect(authGuard({ meta: {}, fullPath: '/x' })).toBe(true)
  })
  it('已登录访问登录页回首页', () => {
    useAuthStore().setToken('t')
    expect(authGuard({ name: 'login', meta: { public: true } })).toEqual({ path: '/' })
  })
  it('未登录访问公开页放行', () => {
    expect(authGuard({ name: 'login', meta: { public: true } })).toBe(true)
  })
  it('路由表包含主数据与各模块', () => {
    const paths = routes[1].children.map((r) => r.path)
    expect(paths).toEqual(expect.arrayContaining(['mdm/materials', 'mdm/customers', 'mdm/suppliers', 'mdm/warehouses', 'purchase', 'sales', 'inventory', 'finance']))
  })
  it('真实 router：未登录被重定向到 /login，标题更新', async () => {
    const router = createAppRouter(createMemoryHistory())
    await router.push('/sales')
    expect(router.currentRoute.value.name).toBe('login')
    expect(router.currentRoute.value.query.redirect).toBe('/sales')
    expect(document.title).toBe('登录 - ERP')
  })
  it('真实 router：登录后 / 重定向到物料', async () => {
    useAuthStore().setToken('t')
    const router = createAppRouter(createMemoryHistory())
    await router.push('/')
    expect(router.currentRoute.value.path).toBe('/mdm/materials')
    await router.push('/nope')
    expect(router.currentRoute.value.path).toBe('/mdm/materials')
  })
})
