import { expect } from '@playwright/test'

/** 调用后端 API（经 preview 代理），同时返回 HTTP 状态和 body */
export async function api(request, method, url, { token, data } = {}) {
  const res = await request.fetch(url, {
    method,
    data,
    headers: token ? { Authorization: `Bearer ${token}` } : {}
  })
  let body = null
  try { body = await res.json() } catch { body = null }
  return { status: res.status(), body }
}

/** 断言 HTTP 状态 + body.code */
export function expectCode(r, status, code) {
  expect(r.status, JSON.stringify(r.body)).toBe(status)
  expect(r.body?.code, JSON.stringify(r.body)).toBe(code)
}

export async function apiLogin(request, username, password) {
  const r = await api(request, 'POST', '/api/auth/login', { data: { username, password } })
  expectCode(r, 200, 0)
  return r.body.data.token
}

export async function uiLogin(page, username, password) {
  await page.locator('input[data-test=username]').fill(username)
  await page.locator('input[data-test=password]').fill(password)
  await page.locator('[data-test=submit]').click()
}

export const uniq = (p) => `${p}${Date.now().toString(36)}${Math.floor(Math.random() * 1e4)}`

const MDM_EXTRA = { materials: { unit: '个' }, customers: {}, suppliers: {}, warehouses: {} }

/** 新建一条主数据（编码带时间戳），断言 200/0 且新行 version 为 0，返回 data */
export async function makeMdm(request, token, res, name = 'e2e') {
  const r = await api(request, 'POST', `/api/mdm/${res}`, {
    token, data: { code: uniq(`E2E-${res.slice(0, 3).toUpperCase()}-`), name, ...MDM_EXTRA[res] }
  })
  expectCode(r, 200, 0)
  expect(r.body.data.version).toBe(0)
  return r.body.data
}

/** 每个测试自建一套主数据，不依赖种子 id */
export async function makeFixture(request, token) {
  const [supplier, warehouse, material, material2, customer] = [
    await makeMdm(request, token, 'suppliers'),
    await makeMdm(request, token, 'warehouses'),
    await makeMdm(request, token, 'materials'),
    await makeMdm(request, token, 'materials'),
    await makeMdm(request, token, 'customers')
  ]
  return { supplierId: supplier.id, warehouseId: warehouse.id, materialId: material.id, material2Id: material2.id, customerId: customer.id }
}
