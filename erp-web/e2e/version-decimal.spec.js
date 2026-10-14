import { test, expect } from '@playwright/test'
import { api, expectCode, apiLogin, uiLogin, uniq, makeFixture } from './helpers.js'

// PR #9：乐观锁 version + 金额/数量/单价以字符串返回 + 溢出 400。
// 只断言 HTTP 状态 + body.code，不断言提示文案。
const ADMIN = { u: 'admin', p: 'admin123' }
const MDM = [
  { res: 'materials', extra: { unit: '个' } },
  { res: 'customers', extra: {} },
  { res: 'suppliers', extra: {} },
  { res: 'warehouses', extra: {} }
]
const isPut = (res, id) => (r) => new URL(r.url()).pathname === `/api/mdm/${res}/${id}` && r.request().method() === 'PUT'
const isList = (res) => (r) => new URL(r.url()).pathname === `/api/mdm/${res}` && r.request().method() === 'GET'

async function create(request, token, m, code) {
  const r = await api(request, 'POST', `/api/mdm/${m.res}`, { token, data: { code, name: 'v0', ...m.extra } })
  expectCode(r, 200, 0)
  expect(r.body.data.version).toBe(0)
  return r.body.data
}

test.describe('乐观锁 version（API）', () => {
  for (const m of MDM) {
    test(`${m.res}：详情和列表带 version；更新 +1；缺 version 400；过期 version 409/40900`, async ({ request }) => {
      const token = await apiLogin(request, ADMIN.u, ADMIN.p)
      const code = uniq('VER-')
      const row = await create(request, token, m, code)
      const d = await api(request, 'GET', `/api/mdm/${m.res}/${row.id}`, { token })
      expectCode(d, 200, 0)
      expect(d.body.data.version).toBe(0)
      const l = await api(request, 'GET', `/api/mdm/${m.res}?keyword=${code}`, { token })
      expectCode(l, 200, 0)
      expect(l.body.data.records).toHaveLength(1)
      expect(l.body.data.records[0].version).toBe(0)

      const ok = await api(request, 'PUT', `/api/mdm/${m.res}/${row.id}`, { token, data: { code, name: 'v1', version: 0 } })
      expectCode(ok, 200, 0)
      expect(ok.body.data.version).toBe(1)
      expectCode(await api(request, 'PUT', `/api/mdm/${m.res}/${row.id}`, { token, data: { code, name: 'stale', version: 0 } }), 409, 40900)
      expectCode(await api(request, 'PUT', `/api/mdm/${m.res}/${row.id}`, { token, data: { code, name: 'nover' } }), 400, 400)
      const after = await api(request, 'GET', `/api/mdm/${m.res}/${row.id}`, { token })
      expect(after.body.data.name).toBe('v1')
      expect(after.body.data.version).toBe(1)
    })
  }

  test('用户 / 角色 / 权限点：更新需要 version，过期 409/40900，缺失 400', async ({ request }) => {
    const token = await apiLogin(request, ADMIN.u, ADMIN.p)
    const role = await api(request, 'POST', '/api/sys/roles', { token, data: { code: `E2E_V_${Date.now()}`, name: 'r' } })
    expectCode(role, 200, 0)
    const rid = role.body.data.id
    const rc = role.body.data.code
    expect(role.body.data.version).toBe(0)
    const rv = role.body.data.version
    const r1 = await api(request, 'PUT', `/api/sys/roles/${rid}`, { token, data: { code: rc, name: 'r1', version: rv } })
    expectCode(r1, 200, 0)
    expect(r1.body.data.version).toBe(rv + 1)
    expectCode(await api(request, 'PUT', `/api/sys/roles/${rid}`, { token, data: { code: rc, name: 'r2', version: rv } }), 409, 40900)
    expectCode(await api(request, 'PUT', `/api/sys/roles/${rid}`, { token, data: { code: rc, name: 'r2' } }), 400, 400)

    const pc = `e2e:v${Date.now()}`
    const perm = await api(request, 'POST', '/api/sys/permissions', { token, data: { code: pc, name: 'p' } })
    expectCode(perm, 200, 0)
    expect(perm.body.data.version).toBe(0)
    const pv = perm.body.data.version
    expectCode(await api(request, 'PUT', `/api/sys/permissions/${perm.body.data.id}`, { token, data: { code: pc, name: 'p1', version: pv } }), 200, 0)
    expectCode(await api(request, 'PUT', `/api/sys/permissions/${perm.body.data.id}`, { token, data: { code: pc, name: 'p2', version: pv } }), 409, 40900)
    expectCode(await api(request, 'PUT', `/api/sys/permissions/${perm.body.data.id}`, { token, data: { code: pc, name: 'p2' } }), 400, 400)

    const u = await api(request, 'POST', '/api/sys/users', { token, data: { username: `e2e_v_${Date.now()}`, password: 'pass123' } })
    expectCode(u, 200, 0)
    const ud = await api(request, 'GET', `/api/sys/users/${u.body.data.id}`, { token })
    expectCode(ud, 200, 0)
    const uv = ud.body.data.version
    expect(uv).toBe(0)
    const u1 = await api(request, 'PUT', `/api/sys/users/${u.body.data.id}`, { token, data: { realName: 'n1', version: uv } })
    expectCode(u1, 200, 0)
    expectCode(await api(request, 'PUT', `/api/sys/users/${u.body.data.id}`, { token, data: { realName: 'n2', version: uv } }), 409, 40900)
    expectCode(await api(request, 'PUT', `/api/sys/users/${u.body.data.id}`, { token, data: { realName: 'n2' } }), 400, 400)
  })
})

test.describe('安全回归：改名提升 version 后停用 / 重置密码仍生效（API）', () => {
  // 预期在小钱修复 setEnabled / resetPassword 忽略 0 行更新之前为红
  test('改名 → 停用：登录失败、旧 token 失效；启用 → 改名 → 重置密码：新密码可登录、旧密码失败', async ({ request }) => {
    const admin = await apiLogin(request, ADMIN.u, ADMIN.p)
    const username = `e2e_sec_${Date.now()}`
    const created = await api(request, 'POST', '/api/sys/users', { token: admin, data: { username, password: 'oldpass1' } })
    expectCode(created, 200, 0)
    const id = created.body.data.id
    const login = (password) => api(request, 'POST', '/api/auth/login', { data: { username, password } })
    const l1 = await login('oldpass1')
    expectCode(l1, 200, 0)
    const userToken = l1.body.data.token
    expectCode(await api(request, 'GET', '/api/auth/me', { token: userToken }), 200, 0)

    const d0 = await api(request, 'GET', `/api/sys/users/${id}`, { token: admin })
    expectCode(d0, 200, 0)
    expect(d0.body.data.version).toBe(0)
    const r1 = await api(request, 'PUT', `/api/sys/users/${id}`, { token: admin, data: { realName: 'n1', version: d0.body.data.version } })
    expectCode(r1, 200, 0)
    expect(r1.body.data.version).toBe(1)

    expectCode(await api(request, 'PUT', `/api/sys/users/${id}/status`, { token: admin, data: { enabled: false } }), 200, 0)
    const dis = await api(request, 'GET', `/api/sys/users/${id}`, { token: admin })
    expect(dis.body.data.enabled).toBe(false)
    expectCode(await login('oldpass1'), 403, 403)
    expectCode(await api(request, 'GET', '/api/auth/me', { token: userToken }), 401, 401)

    expectCode(await api(request, 'PUT', `/api/sys/users/${id}/status`, { token: admin, data: { enabled: true } }), 200, 0)
    const d2 = await api(request, 'GET', `/api/sys/users/${id}`, { token: admin })
    expectCode(d2, 200, 0)
    expect(d2.body.data.enabled).toBe(true)
    expectCode(await api(request, 'PUT', `/api/sys/users/${id}`, { token: admin, data: { realName: 'n2', version: d2.body.data.version } }), 200, 0)
    expectCode(await api(request, 'PUT', `/api/sys/users/${id}/password`, { token: admin, data: { password: 'newpass2' } }), 200, 0)
    expectCode(await login('newpass2'), 200, 0)
    expectCode(await login('oldpass1'), 401, 401)
  })
})

test.describe('乐观锁真实冲突（两个浏览器上下文）', () => {
  for (const m of MDM) {
    test(`${m.res}：A 先保存 200/0，B 再保存 409/40900，B 的列表刷新一次显示 A 的值，只有一条提示`, async ({ browser, request }) => {
      const token = await apiLogin(request, ADMIN.u, ADMIN.p)
      const code = uniq('CF-')
      const row = await create(request, token, m, code)
      const [ca, cb] = [await browser.newContext(), await browser.newContext()]
      const [a, b] = [await ca.newPage(), await cb.newPage()]
      for (const p of [a, b]) {
        await p.goto('/login')
        await uiLogin(p, ADMIN.u, ADMIN.p)
        await expect(p).toHaveURL(/\/mdm\/materials$/)
        await p.goto(`/mdm/${m.res}`)
        await p.locator('input[data-test=keyword]').fill(code)
        await p.locator('[data-test=search]').click()
        const tr = p.locator('.el-table__body tr').filter({ hasText: code })
        await expect(tr).toHaveCount(1)
        await tr.locator('[data-test=edit]').click()
        await expect(p.locator('input[data-test=field-name]')).toHaveValue('v0')
      }
      // A 保存
      await a.locator('input[data-test=field-name]').fill('A-value')
      const ra = a.waitForResponse(isPut(m.res, row.id))
      await a.locator('[data-test=save]').click()
      const resA = await ra
      expect(resA.status()).toBe(200)
      expect((await resA.json()).code).toBe(0)
      // B 用旧 version 保存
      const lists = []
      b.on('response', (r) => { if (isList(m.res)(r)) lists.push(r.status()) })
      await b.locator('input[data-test=field-name]').fill('B-value')
      const rbReq = b.waitForRequest((q) => new URL(q.url()).pathname === `/api/mdm/${m.res}/${row.id}` && q.method() === 'PUT')
      const rb = b.waitForResponse(isPut(m.res, row.id))
      await b.locator('[data-test=save]').click()
      expect((await rbReq).postDataJSON().version).toBe(0)
      const resB = await rb
      expect(resB.status()).toBe(409)
      expect((await resB.json()).code).toBe(40900)
      await expect(b.locator('.el-table__body tr').filter({ hasText: code })).toContainText('A-value', { timeout: 5000 })
      await b.waitForTimeout(800)
      expect(lists.length, `B list refresh count: ${lists}`).toBe(1)
      await expect(b.locator('.el-message')).toHaveCount(1)
      const d = await api(request, 'GET', `/api/mdm/${m.res}/${row.id}`, { token })
      expect(d.body.data.name).toBe('A-value')
      expect(d.body.data.version).toBe(1)
      await ca.close(); await cb.close()
    })
  }
})

test.describe('金额 / 数量 / 单价以字符串返回（API）', () => {
  const BIG = '1234567890123456.12'

  test('收付款：字符串和数字入参都能精确回读字符串；"abc" → 400/400', async ({ request }) => {
    const token = await apiLogin(request, ADMIN.u, ADMIN.p)
    const f = await makeFixture(request, token)
    const s = await api(request, 'POST', '/api/finance/payments', { token, data: { paymentType: 'PAYMENT', partnerId: f.supplierId, amount: BIG } })
    expectCode(s, 200, 0)
    expect(typeof s.body.data.amount).toBe('string')
    expect(s.body.data.amount).toBe(BIG)
    expect(typeof s.body.data.appliedAmount).toBe('string')
    const n = await api(request, 'POST', '/api/finance/payments', { token, data: { paymentType: 'PAYMENT', partnerId: f.supplierId, amount: 12.5 } })
    expectCode(n, 200, 0)
    expect(n.body.data.amount).toBe('12.50')
    const list = await api(request, 'GET', '/api/finance/payments?size=100', { token })
    expectCode(list, 200, 0)
    const big = list.body.data.records.find((r) => r.id === s.body.data.id)
    expect(big.amount).toBe(BIG)
    for (const r of list.body.data.records) {
      expect(typeof r.amount).toBe('string')
      expect(typeof r.appliedAmount).toBe('string')
    }
    expectCode(await api(request, 'POST', '/api/finance/payments', { token, data: { paymentType: 'PAYMENT', partnerId: f.supplierId, amount: 'abc' } }), 400, 400)
  })

  test('采购单行：qty / price / amount / totalAmount 都是字符串，详情和列表一致；"abc" → 400/400', async ({ request }) => {
    const token = await apiLogin(request, ADMIN.u, ADMIN.p)
    const f = await makeFixture(request, token)
    const c = await api(request, 'POST', '/api/purchase/orders', {
      token, data: { supplierId: f.supplierId, warehouseId: f.warehouseId, items: [{ materialId: f.materialId, qty: '2.5', price: '3.333' }, { materialId: f.material2Id, qty: 3, price: 1.1 }] }
    })
    expectCode(c, 200, 0)
    const { order, items } = c.body.data
    expect(typeof order.totalAmount).toBe('string')
    for (const it of items) for (const f of ['qty', 'price', 'amount']) expect(typeof it[f], f).toBe('string')
    expect(items[0].amount).toBe('8.33')
    expect(items[1].amount).toBe('3.30')
    expect(order.totalAmount).toBe('11.63')
    const d = await api(request, 'GET', `/api/purchase/orders/${order.id}`, { token })
    expectCode(d, 200, 0)
    expect(d.body.data.order.totalAmount).toBe('11.63')
    expect(d.body.data.items.map((i) => [i.qty, i.price, i.amount])).toEqual(items.map((i) => [i.qty, i.price, i.amount]))
    const l = await api(request, 'GET', '/api/purchase/orders?size=100', { token })
    expect(l.body.data.records.find((r) => r.id === order.id).totalAmount).toBe('11.63')
    expectCode(await api(request, 'POST', '/api/purchase/orders', {
      token, data: { supplierId: f.supplierId, warehouseId: f.warehouseId, items: [{ materialId: f.materialId, qty: 'abc', price: 1 }] }
    }), 400, 400)
    expectCode(await api(request, 'POST', '/api/purchase/orders', {
      token, data: { supplierId: f.supplierId, warehouseId: f.warehouseId, items: [{ materialId: f.materialId, qty: 1, price: 'abc' }] }
    }), 400, 400)
  })

  test('行金额溢出（99999999999999 × 99999999999999）→ 400/400，不是 500', async ({ request }) => {
    const token = await apiLogin(request, ADMIN.u, ADMIN.p)
    const f = await makeFixture(request, token)
    for (const v of [99999999999999, '99999999999999']) {
      expectCode(await api(request, 'POST', '/api/purchase/orders', {
        token, data: { supplierId: f.supplierId, warehouseId: f.warehouseId, items: [{ materialId: f.materialId, qty: v, price: v }] }
      }), 400, 400)
    }
  })

  // 目前 erp-web 还没有采购 / 财务页面（占位“开发中”），金额显示的 UI 校验等页面上线后再补
  test.skip('UI：采购 / 收付款页面显示的金额没有被四舍五入或失真（等页面上线）', async () => {})
})
