import { test, expect } from '@playwright/test'
import { api, expectCode, apiLogin, uiLogin, uniq } from './helpers.js'

const ADMIN = { u: 'admin', p: 'admin123' }
const LISTS = [
  { path: '/mdm/materials', api: '/api/mdm/materials', title: '物料管理', demo: 'DM-M001' },
  { path: '/mdm/customers', api: '/api/mdm/customers', title: '客户管理', demo: 'DM-C001' },
  { path: '/mdm/suppliers', api: '/api/mdm/suppliers', title: '供应商管理', demo: 'DM-S001' },
  { path: '/mdm/warehouses', api: '/api/mdm/warehouses', title: '仓库管理', demo: 'DM-W01' }
]

test.describe('登录与路由守卫', () => {
  test('未登录访问受保护页面 → 跳转登录页并带 redirect', async ({ page }) => {
    await page.goto('/mdm/customers')
    await expect(page).toHaveURL(/\/login\?redirect=(\/|%2F)mdm(\/|%2F)customers/)
  })

  test('错误密码 → 提示错误、停留在登录页、无 token', async ({ page }) => {
    await page.goto('/login')
    const resp = page.waitForResponse((r) => r.url().includes('/api/auth/login'))
    await uiLogin(page, ADMIN.u, 'wrong-password')
    const r = await resp
    expect(r.status()).toBe(401)
    expect((await r.json()).code).toBe(401)
    await expect(page.locator('.el-message')).toContainText('用户名或密码错误')
    await expect(page).toHaveURL(/\/login/)
    expect(await page.evaluate(() => localStorage.getItem('erp_token'))).toBeNull()
  })

  test('admin 登录成功 → 回到 redirect 页，顶栏显示用户名；退出登录清 token', async ({ page }) => {
    await page.goto('/mdm/suppliers')
    await expect(page).toHaveURL(/\/login/)
    const resp = page.waitForResponse((r) => r.url().includes('/api/auth/login'))
    await uiLogin(page, ADMIN.u, ADMIN.p)
    const r = await resp
    expect(r.status()).toBe(200)
    expect((await r.json()).code).toBe(0)
    await expect(page).toHaveURL(/\/mdm\/suppliers$/)
    await expect(page.locator('header, .el-header')).toContainText('admin')
    expect(await page.evaluate(() => localStorage.getItem('erp_token'))).toBeTruthy()

    await page.locator('[data-test=logout]').click()
    await expect(page).toHaveURL(/\/login/)
    expect(await page.evaluate(() => localStorage.getItem('erp_token'))).toBeNull()
  })
})

test.describe('主数据列表页', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/login')
    await uiLogin(page, ADMIN.u, ADMIN.p)
    await expect(page).toHaveURL(/\/mdm\/materials$/)
  })

  for (const l of LISTS) {
    test(`${l.title}：加载演示数据，且只发 1 次列表请求`, async ({ page }) => {
      await page.goto('/') // 先离开，避免统计到登录跳转产生的请求
      await page.waitForLoadState('networkidle')
      const reqs = []
      const statuses = []
      page.on('request', (q) => { if (new URL(q.url()).pathname === l.api && q.method() === 'GET') reqs.push(q.url()) })
      page.on('response', async (s) => {
        if (new URL(s.url()).pathname === l.api) statuses.push([s.status(), (await s.json()).code])
      })
      await page.goto(l.path)
      await expect(page.locator('.el-table__body')).toContainText(l.demo)
      await page.waitForLoadState('networkidle')
      expect(reqs.length, reqs.join('\n')).toBe(1)
      expect(statuses.every(([s, c]) => s === 200 && c === 0)).toBe(true)
    })
  }
})

test.describe('只读用户（无 mdm:write）', () => {
  let user, token
  test.beforeAll(async ({ request }) => {
    const admin = await apiLogin(request, ADMIN.u, ADMIN.p)
    const roleCode = uniq('E2E_RO_')
    const role = await api(request, 'POST', '/api/sys/roles', { token: admin, data: { code: roleCode, name: 'e2e 只读' } })
    expectCode(role, 200, 0)
    // 只读：不授予任何写权限点（主数据查询只需登录）
    expectCode(await api(request, 'PUT', `/api/sys/roles/${role.body.data.id}/permissions`, { token: admin, data: { ids: [] } }), 200, 0)
    user = { u: uniq('e2e_ro_'), p: 'readonly123' }
    const created = await api(request, 'POST', '/api/sys/users', {
      token: admin, data: { username: user.u, password: user.p, realName: 'e2e 只读', roleIds: [role.body.data.id] }
    })
    expectCode(created, 200, 0)
    token = await apiLogin(request, user.u, user.p)
  })

  test('UI 登录后能看列表，页面上没有写操作按钮', async ({ page }) => {
    await page.goto('/login')
    await uiLogin(page, user.u, user.p)
    await expect(page).toHaveURL(/\/mdm\/materials$/)
    await expect(page.locator('.el-table__body')).toContainText('DM-M001')
    for (const name of ['新增', '新建', '编辑', '删除', '停用']) {
      await expect(page.getByRole('button', { name })).toHaveCount(0)
    }
  })

  test('直接调用写接口 → 403 / code 403', async ({ request }) => {
    expectCode(await api(request, 'POST', '/api/mdm/materials', { token, data: { code: uniq('RO-'), name: 'x' } }), 403, 403)
    expectCode(await api(request, 'PUT', '/api/mdm/materials/1', { token, data: { code: 'DM-M001', name: 'x', status: 1 } }), 403, 403)
    expectCode(await api(request, 'DELETE', '/api/mdm/customers/1', { token }), 403, 403)
    expectCode(await api(request, 'POST', '/api/sys/roles', { token, data: { code: uniq('X'), name: 'x' } }), 403, 403)
  })
})

test.describe('引用保护 40901（API）', () => {
  test('客户只有收款记录时删除 → 409 / 40901', async ({ request }) => {
    const admin = await apiLogin(request, ADMIN.u, ADMIN.p)
    const c = await api(request, 'POST', '/api/mdm/customers', { token: admin, data: { code: uniq('E2E-C-'), name: 'e2e 收款客户' } })
    expectCode(c, 200, 0)
    const pay = await api(request, 'POST', '/api/finance/payments', {
      token: admin, data: { paymentType: 'RECEIPT', partnerId: c.body.data.id, amount: 10 }
    })
    expectCode(pay, 200, 0)
    const del = await api(request, 'DELETE', `/api/mdm/customers/${c.body.data.id}`, { token: admin })
    expectCode(del, 409, 40901)
  })

  test('未带 token 调接口 → 401 / code 401', async ({ request }) => {
    expectCode(await api(request, 'GET', '/api/mdm/materials'), 401, 401)
  })
})

// TODO(#5)：主数据 CRUD 页面合入后去掉 skip，并按实际 data-test 选择器调整
test.describe.skip('主数据 CRUD UI（等 #5 合入后启用）', () => {
  test('新增物料 → 列表出现新编码', async () => {})
  test('编辑物料 → PUT 请求体里的 code 和原来一致（编码不可改）', async () => {})
  test('停用物料 → 状态显示“停用”', async () => {})
  test('删除被引用的物料 → 界面提示 40901 文案“已被…引用，不能删除，只能停用”', async () => {})
})
