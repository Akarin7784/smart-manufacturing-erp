import { test, expect } from '@playwright/test'
import { api, expectCode, apiLogin, uiLogin, uniq } from './helpers.js'

// PR #9 最终验收：页面编辑清空选填字段、弹窗停用后立即再编辑、双浏览器冲突后刷新重试。
// 后端响应只断言 HTTP 状态 + body.code；40900 的前端提示文案属于 UI，可以断言。
const ADMIN = { u: 'admin', p: 'admin123' }
// 每类主数据挑一个选填字段做清空
const MDM = [
  { res: 'materials', extra: { unit: '个' }, opt: 'spec' },
  { res: 'customers', extra: {}, opt: 'contact' },
  { res: 'suppliers', extra: {}, opt: 'phone' },
  { res: 'warehouses', extra: {}, opt: 'address' }
]
const isPut = (res, id) => (r) => new URL(r.url()).pathname === `/api/mdm/${res}/${id}` && r.request().method() === 'PUT'
const isList = (res) => (r) => new URL(r.url()).pathname === `/api/mdm/${res}` && r.request().method() === 'GET'

async function create(request, token, m, extra = {}) {
  const code = uniq('FIN-')
  const r = await api(request, 'POST', `/api/mdm/${m.res}`, { token, data: { code, name: 'v0', ...m.extra, ...extra } })
  expectCode(r, 200, 0)
  expect(r.body.data.version).toBe(0)
  return r.body.data
}

async function openList(page, res, code) {
  await page.goto('/login')
  await uiLogin(page, ADMIN.u, ADMIN.p)
  await expect(page).toHaveURL(/\/mdm\/materials$/)
  await page.goto(`/mdm/${res}`)
  await page.locator('input[data-test=keyword]').fill(code)
  await page.locator('[data-test=search]').click()
  await expect(page.locator('.el-table__body tr').filter({ hasText: code })).toHaveCount(1)
}
const row = (page, code) => page.locator('.el-table__body tr').filter({ hasText: code })

async function save(page, res, id) {
  const req = page.waitForRequest((q) => new URL(q.url()).pathname === `/api/mdm/${res}/${id}` && q.method() === 'PUT')
  const resp = page.waitForResponse(isPut(res, id))
  await page.locator('[data-test=save]').click()
  const r = await resp
  return { body: (await req).postDataJSON(), status: r.status(), code: (await r.json()).code }
}

test.describe('页面编辑：清空选填字段', () => {
  for (const m of MDM) {
    test(`${m.res}：清空 ${m.opt} 保存，PUT 传空串，API 回读 ""，重开弹窗为空`, async ({ page, request }) => {
      const token = await apiLogin(request, ADMIN.u, ADMIN.p)
      const d0 = await create(request, token, m, { [m.opt]: 'to-clear' })
      expect(d0[m.opt]).toBe('to-clear')
      await openList(page, m.res, d0.code)
      await row(page, d0.code).locator('[data-test=edit]').click()
      await expect(page.locator(`input[data-test=field-${m.opt}]`)).toHaveValue('to-clear')
      await page.locator(`input[data-test=field-${m.opt}]`).fill('')
      const s = await save(page, m.res, d0.id)
      expect(s.body[m.opt]).toBe('')
      expect(s.body.version).toBe(0)
      expect(s.status).toBe(200)
      expect(s.code).toBe(0)
      const d = await api(request, 'GET', `/api/mdm/${m.res}/${d0.id}`, { token })
      expectCode(d, 200, 0)
      expect(d.body.data[m.opt]).toBe('')
      expect(d.body.data.version).toBe(1)
      await expect(row(page, d0.code)).not.toContainText('to-clear')
      await row(page, d0.code).locator('[data-test=edit]').click()
      await expect(page.locator(`input[data-test=field-${m.opt}]`)).toHaveValue('')
    })
  }
})

test.describe('页面编辑：弹窗停用后不刷新页面立即再编辑', () => {
  for (const m of MDM) {
    test(`${m.res}：停用保存 → 立即重开同一行改名保存 200/0，无 40900，version +2`, async ({ page, request }) => {
      const token = await apiLogin(request, ADMIN.u, ADMIN.p)
      const d0 = await create(request, token, m)
      await openList(page, m.res, d0.code)
      const codes = []
      page.on('response', async (r) => { if (isPut(m.res, d0.id)(r)) codes.push((await r.json().catch(() => ({}))).code) })
      await row(page, d0.code).locator('[data-test=edit]').click()
      await page.locator('[data-test=field-status]').getByText('停用').click()
      const listAfter = page.waitForResponse(isList(m.res))
      const s1 = await save(page, m.res, d0.id)
      expect([s1.status, s1.code, s1.body.status, s1.body.version]).toEqual([200, 0, 0, 0])
      await listAfter
      await expect(row(page, d0.code)).toContainText('停用')
      await row(page, d0.code).locator('[data-test=edit]').click()
      await page.locator('input[data-test=field-name]').fill('after-disable')
      const s2 = await save(page, m.res, d0.id)
      expect(s2.body.version).toBe(1)
      expect([s2.status, s2.code]).toEqual([200, 0])
      await expect(row(page, d0.code)).toContainText('after-disable')
      await expect(page.getByText('数据已被修改，请刷新后重试')).toHaveCount(0)
      expect(codes).toEqual([0, 0])
      const d = await api(request, 'GET', `/api/mdm/${m.res}/${d0.id}`, { token })
      expectCode(d, 200, 0)
      expect(d.body.data.version).toBe(2)
      expect(d.body.data.status).toBe(0)
      expect(d.body.data.name).toBe('after-disable')
    })
  }
})

test.describe('双浏览器冲突：提示文案 + 刷新后重试成功', () => {
  for (const m of MDM) {
    test(`${m.res}：A 保存，B 409/40900 提示「数据已被修改，请刷新后重试」，数据为 A；B 重开重试 200/0`, async ({ browser, request }) => {
      const token = await apiLogin(request, ADMIN.u, ADMIN.p)
      const d0 = await create(request, token, m)
      const [ca, cb] = [await browser.newContext(), await browser.newContext()]
      const [a, b] = [await ca.newPage(), await cb.newPage()]
      for (const p of [a, b]) {
        await openList(p, m.res, d0.code)
        await row(p, d0.code).locator('[data-test=edit]').click()
        await expect(p.locator('input[data-test=field-name]')).toHaveValue('v0')
      }
      await a.locator('input[data-test=field-name]').fill('A-value')
      const sa = await save(a, m.res, d0.id)
      expect([sa.status, sa.code]).toEqual([200, 0])
      await b.locator('input[data-test=field-name]').fill('B-value')
      const sb = await save(b, m.res, d0.id)
      expect([sb.status, sb.code, sb.body.version]).toEqual([409, 40900, 0])
      await expect(b.getByText('数据已被修改，请刷新后重试')).toBeVisible()
      let d = await api(request, 'GET', `/api/mdm/${m.res}/${d0.id}`, { token })
      expect([d.body.data.name, d.body.data.version]).toEqual(['A-value', 1])
      await expect(row(b, d0.code)).toContainText('A-value')
      await row(b, d0.code).locator('[data-test=edit]').click()
      await expect(b.locator('input[data-test=field-name]')).toHaveValue('A-value')
      await b.locator('input[data-test=field-name]').fill('B-retry')
      const sr = await save(b, m.res, d0.id)
      expect([sr.status, sr.code, sr.body.version]).toEqual([200, 0, 1])
      d = await api(request, 'GET', `/api/mdm/${m.res}/${d0.id}`, { token })
      expect([d.body.data.name, d.body.data.version]).toEqual(['B-retry', 2])
      await ca.close(); await cb.close()
    })
  }
})
