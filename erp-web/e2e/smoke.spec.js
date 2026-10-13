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
        if (new URL(s.url()).pathname !== l.api) return
        let code
        try { code = (await s.json()).code } catch { code = 'non-json' }
        statuses.push([s.status(), code])
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
  // 用户和角色名带时间戳，每次运行唯一；CI 用的是 H2 内存库，每次都是全新的。
  // TODO：如果改为连持久库（MySQL 等）运行，需要在 afterAll 里删除这里建的用户和角色，避免数据越积越多。
  test.beforeAll(async ({ request }) => {
    const admin = await apiLogin(request, ADMIN.u, ADMIN.p)
    const roleCode = `E2E_RO_${Date.now()}`
    const role = await api(request, 'POST', '/api/sys/roles', { token: admin, data: { code: roleCode, name: 'e2e 只读' } })
    expectCode(role, 200, 0)
    // 只读：不授予任何写权限点（主数据查询只需登录）
    expectCode(await api(request, 'PUT', `/api/sys/roles/${role.body.data.id}/permissions`, { token: admin, data: { ids: [] } }), 200, 0)
    user = { u: `e2e_ro_${Date.now()}`, p: 'readonly123' }
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
    expectCode(await api(request, 'PUT', '/api/mdm/materials/1', { token, data: { code: 'DM-M001', name: 'x', status: 1, version: 0 } }), 403, 403)
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

// 主数据 CRUD 页面（#5）
const CRUD = [
  { res: 'materials', title: '物料', extra: { unit: '个' }, ref: 'po' },
  { res: 'customers', title: '客户', extra: { contact: 'e2e' }, ref: 'receipt' },
  { res: 'suppliers', title: '供应商', extra: { contact: 'e2e' }, ref: 'po' },
  { res: 'warehouses', title: '仓库', extra: { address: 'e2e' }, ref: 'po' }
]

async function adminUi(page) {
  await page.goto('/login')
  await uiLogin(page, ADMIN.u, ADMIN.p)
  await expect(page).toHaveURL(/\/mdm\/materials$/)
}
const rowOf = (page, code) => page.locator('.el-table__body tr').filter({ hasText: code })
async function search(page, code) {
  await page.locator('input[data-test=keyword]').fill(code)
  await page.locator('[data-test=search]').click()
  await expect(rowOf(page, code)).toHaveCount(1)
}
async function apiCreate(request, token, c, code) {
  const r = await api(request, 'POST', `/api/mdm/${c.res}`, { token, data: { code, name: `e2e ${c.title}`, ...c.extra } })
  expectCode(r, 200, 0)
  return r.body.data.id
}
/** 让该主数据被单据引用：物料/供应商/仓库用一张草稿采购单，客户用一笔收款 */
async function makeReferenced(request, token, c, id) {
  if (c.ref === 'receipt') {
    expectCode(await api(request, 'POST', '/api/finance/payments', { token, data: { paymentType: 'RECEIPT', partnerId: id, amount: 1 } }), 200, 0)
    return
  }
  const ids = { supplierId: 1, warehouseId: 1, materialId: 1 }
  if (c.res === 'materials') ids.materialId = id
  if (c.res === 'suppliers') ids.supplierId = id
  if (c.res === 'warehouses') ids.warehouseId = id
  const po = await api(request, 'POST', '/api/purchase/orders', {
    token, data: { supplierId: ids.supplierId, warehouseId: ids.warehouseId, items: [{ materialId: ids.materialId, qty: 1, price: 1 }] }
  })
  expectCode(po, 200, 0)
}

for (const c of CRUD) {
  test.describe(`主数据 CRUD UI：${c.title}`, () => {
    test('新增 → POST 200/0，列表出现新编码', async ({ page }) => {
      await adminUi(page)
      await page.goto(`/mdm/${c.res}`)
      const code = uniq(`E2E-${c.res.slice(0, 3).toUpperCase()}-`)
      await page.locator('[data-test=create]').click()
      await page.locator('input[data-test=field-code]').fill(code)
      await page.locator('input[data-test=field-name]').fill(`新增${c.title}`)
      const resp = page.waitForResponse((r) => new URL(r.url()).pathname === `/api/mdm/${c.res}` && r.request().method() === 'POST')
      await page.locator('[data-test=save]').click()
      const r = await resp
      expect(r.status()).toBe(200)
      expect((await r.json()).code).toBe(0)
      await search(page, code)
      await expect(rowOf(page, code)).toContainText(`新增${c.title}`)
    })

    test('编辑 → PUT 请求体带原编码，200/0，列表显示新名称', async ({ page, request }) => {
      const token = await apiLogin(request, ADMIN.u, ADMIN.p)
      const code = uniq(`E2E-ED-${c.res.slice(0, 3)}-`)
      const id = await apiCreate(request, token, c, code)
      await adminUi(page)
      await page.goto(`/mdm/${c.res}`)
      await search(page, code)
      await rowOf(page, code).locator('[data-test=edit]').click()
      await expect(page.locator('input[data-test=field-code]')).toBeDisabled()
      const newName = `改名${Date.now()}`
      await page.locator('input[data-test=field-name]').fill(newName)
      const reqP = page.waitForRequest((q) => new URL(q.url()).pathname === `/api/mdm/${c.res}/${id}` && q.method() === 'PUT')
      const resP = page.waitForResponse((r) => new URL(r.url()).pathname === `/api/mdm/${c.res}/${id}` && r.request().method() === 'PUT')
      await page.locator('[data-test=save]').click()
      const body = (await reqP).postDataJSON()
      expect(body.code).toBe(code)
      expect(body.name).toBe(newName)
      // 乐观锁：PUT 必须带当前行的 version（新建行为 0）
      expect(body.version).toBe(0)
      const r = await resP
      expect(r.status()).toBe(200)
      const rb = await r.json()
      expect(rb.code).toBe(0)
      expect(rb.data.version).toBe(1)
      await expect(rowOf(page, code)).toContainText(newName)
    })

    test('停用 → 200/0，刷新后状态仍为“停用”', async ({ page, request }) => {
      const token = await apiLogin(request, ADMIN.u, ADMIN.p)
      const code = uniq(`E2E-DS-${c.res.slice(0, 3)}-`)
      const id = await apiCreate(request, token, c, code)
      await adminUi(page)
      await page.goto(`/mdm/${c.res}`)
      await search(page, code)
      await expect(rowOf(page, code)).toContainText('启用')
      await rowOf(page, code).locator('[data-test=edit]').click()
      await page.locator('[data-test=field-status]').getByText('停用').click()
      const resP = page.waitForResponse((r) => new URL(r.url()).pathname === `/api/mdm/${c.res}/${id}` && r.request().method() === 'PUT')
      await page.locator('[data-test=save]').click()
      const r = await resP
      expect(r.status()).toBe(200)
      expect((await r.json()).code).toBe(0)
      await page.reload()
      await search(page, code)
      await expect(rowOf(page, code)).toContainText('停用')
      const g = await api(request, 'GET', `/api/mdm/${c.res}/${id}`, { token })
      expectCode(g, 200, 0)
      expect(g.body.data.status).toBe(0)
    })

    test('删除被引用的数据 → 409/40901，提示只出现一次，行仍在', async ({ page, request }) => {
      const token = await apiLogin(request, ADMIN.u, ADMIN.p)
      const code = uniq(`E2E-RF-${c.res.slice(0, 3)}-`)
      const id = await apiCreate(request, token, c, code)
      await makeReferenced(request, token, c, id)
      await adminUi(page)
      await page.goto(`/mdm/${c.res}`)
      await search(page, code)
      await rowOf(page, code).locator('[data-test=delete]').click()
      const resP = page.waitForResponse((r) => new URL(r.url()).pathname === `/api/mdm/${c.res}/${id}` && r.request().method() === 'DELETE')
      await page.locator('.el-message-box').getByRole('button', { name: '删除' }).click()
      const r = await resP
      expect(r.status()).toBe(409)
      expect((await r.json()).code).toBe(40901)
      const msg = page.locator('.el-message').filter({ hasText: '该数据已被单据引用，不能删除，可改为停用' })
      await expect(msg).toHaveCount(1)
      await page.waitForTimeout(500)
      await expect(msg).toHaveCount(1)
      await expect(page.locator('.el-message--success')).toHaveCount(0)
      await expect(rowOf(page, code)).toHaveCount(1)
      expectCode(await api(request, 'GET', `/api/mdm/${c.res}/${id}`, { token }), 200, 0)
    })
  })
}
