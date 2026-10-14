import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { setLoginRedirect } from '@/utils/request'

const Placeholder = () => import('@/views/PlaceholderView.vue')
const MdmList = () => import('@/views/mdm/MdmListView.vue')

export const routes = [
  { path: '/login', name: 'login', component: () => import('@/views/LoginView.vue'), meta: { public: true, title: '登录' } },
  {
    path: '/',
    component: () => import('@/layout/MainLayout.vue'),
    redirect: '/mdm/materials',
    children: [
      { path: 'mdm/materials', name: 'mdm-materials', component: MdmList, props: { resource: 'materials' }, meta: { title: '物料管理' } },
      { path: 'mdm/customers', name: 'mdm-customers', component: MdmList, props: { resource: 'customers' }, meta: { title: '客户管理' } },
      { path: 'mdm/suppliers', name: 'mdm-suppliers', component: MdmList, props: { resource: 'suppliers' }, meta: { title: '供应商管理' } },
      { path: 'mdm/warehouses', name: 'mdm-warehouses', component: MdmList, props: { resource: 'warehouses' }, meta: { title: '仓库管理' } },
      { path: 'purchase', name: 'purchase', component: () => import('@/views/purchase/PurchaseOrderListView.vue'), meta: { title: '采购管理' } },
      { path: 'sales', name: 'sales', component: Placeholder, meta: { title: '销售管理' } },
      { path: 'inventory', name: 'inventory', component: Placeholder, meta: { title: '库存管理' } },
      { path: 'finance', name: 'finance', component: Placeholder, meta: { title: '财务管理' } }
    ]
  },
  { path: '/:pathMatch(.*)*', redirect: '/' }
]

/** 路由守卫：未登录访问受保护页 -> 登录页（带 redirect）；已登录访问登录页 -> 首页 */
export function authGuard(to) {
  const auth = useAuthStore()
  if (to.meta?.public) {
    if (to.name === 'login' && auth.isLoggedIn) return { path: '/' }
    return true
  }
  if (!auth.isLoggedIn) return { name: 'login', query: { redirect: to.fullPath } }
  return true
}

export function createAppRouter(history = createWebHistory()) {
  const router = createRouter({ history, routes })
  router.beforeEach(authGuard)
  router.afterEach((to) => {
    document.title = to.meta?.title ? `${to.meta.title} - ERP` : 'ERP'
  })
  setLoginRedirect(() => {
    const current = router.currentRoute.value
    if (current.name !== 'login') router.replace({ name: 'login', query: { redirect: current.fullPath } })
  })
  return router
}

export default createAppRouter()
