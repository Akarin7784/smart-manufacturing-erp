import { setActivePinia, createPinia } from 'pinia'
import { ElMessage } from 'element-plus'
import request, { attachToken, onFulfilled, onRejected, handleStatus, setLoginRedirect, MSG, BizError } from '@/utils/request'
import { useAuthStore } from '@/stores/auth'

vi.spyOn(ElMessage, 'error').mockImplementation(() => {})

const res = (body, config = { url: '/api/x' }) => ({ data: body, config })
const err = (status, body, config = { url: '/api/x' }) => ({ config, response: { status, data: body } })

describe('request 拦截器', () => {
  let redirect
  beforeEach(() => {
    setActivePinia(createPinia())
    redirect = vi.fn()
    setLoginRedirect(redirect)
    ElMessage.error.mockClear()
  })

  it('有 token 时附加 Bearer 头', () => {
    useAuthStore().setToken('jwt1')
    expect(attachToken({ headers: {} }).headers.Authorization).toBe('Bearer jwt1')
    expect(attachToken({}).headers.Authorization).toBe('Bearer jwt1')
  })
  it('无 token 不加头', () => {
    expect(attachToken({ headers: {} }).headers.Authorization).toBeUndefined()
  })
  it('code=0 返回 data', async () => {
    expect(await onFulfilled(res({ code: 0, msg: 'ok', data: { a: 1 } }))).toEqual({ a: 1 })
  })
  it('非统一结构原样返回', async () => {
    expect(await onFulfilled(res('plain'))).toBe('plain')
  })
  it('业务错误码提示 msg 并 reject', async () => {
    await expect(onFulfilled(res({ code: 400, msg: '库存不足' }))).rejects.toMatchObject({ code: 400, message: '库存不足' })
    expect(ElMessage.error).toHaveBeenCalledWith('库存不足')
  })
  it('HTTP 401 清 token 并跳转登录', async () => {
    useAuthStore().setToken('t')
    await expect(onRejected(err(401, { code: 401, msg: '未登录或登录已失效', data: null }))).rejects.toBeInstanceOf(BizError)
    expect(useAuthStore().token).toBe('')
    expect(redirect).toHaveBeenCalled()
    expect(ElMessage.error).toHaveBeenCalledWith(MSG.unauthorized)
  })
  it('登录接口返回 401 业务码不跳转，提示后端 msg', async () => {
    await expect(onFulfilled(res({ code: 401, msg: '用户名或密码错误' }, { url: '/api/auth/login' }))).rejects.toMatchObject({ code: 401 })
    expect(redirect).not.toHaveBeenCalled()
    expect(ElMessage.error).toHaveBeenCalledWith('用户名或密码错误')
    expect(handleStatus(401, { url: '/api/auth/login' })).toBe('用户名或密码错误')
  })
  it('403（HTTP 或业务码）提示无权限', async () => {
    await expect(onRejected(err(403, {}))).rejects.toMatchObject({ code: 403 })
    await expect(onFulfilled(res({ code: 403, msg: '无权限' }))).rejects.toMatchObject({ code: 403 })
    expect(ElMessage.error).toHaveBeenNthCalledWith(1, MSG.forbidden)
    expect(ElMessage.error).toHaveBeenNthCalledWith(2, MSG.forbidden)
    expect(handleStatus(403, {}, '账号已停用')).toBe('账号已停用')
  })
  it('409 提示数据已被修改', async () => {
    await expect(onRejected(err(409, { code: 409, msg: 'x' }))).rejects.toMatchObject({ code: 409 })
    await expect(onFulfilled(res({ code: 409, msg: 'x' }))).rejects.toMatchObject({ code: 409 })
    expect(ElMessage.error).toHaveBeenCalledWith(MSG.conflict)
  })
  it('HTTP 400/404 以状态码判失败并提示后端 msg', async () => {
    await expect(onRejected(err(400, { code: 400, msg: 'name 不能为空', data: null }))).rejects.toMatchObject({ code: 400, message: 'name 不能为空' })
    await expect(onRejected(err(404, { code: 404, msg: '物料不存在' }))).rejects.toMatchObject({ code: 404, message: '物料不存在' })
    await expect(onRejected(err(404, null))).rejects.toMatchObject({ code: 404, message: MSG.notFound })
  })
  it('HTTP 409 即使 body.code 不同也以状态码为准', async () => {
    await expect(onRejected(err(409, { code: 0, msg: 'ok' }))).rejects.toMatchObject({ code: 409, message: MSG.conflict })
  })
  it('5xx 与网络错误', async () => {
    await expect(onRejected(err(500, null))).rejects.toMatchObject({ code: 500, message: MSG.server })
    await expect(onRejected({ config: {} })).rejects.toMatchObject({ code: 0, message: MSG.network })
  })
  it('silent 不弹提示', async () => {
    await expect(onFulfilled(res({ code: 400, msg: 'x' }, { url: '/a', silent: true }))).rejects.toBeTruthy()
    expect(ElMessage.error).not.toHaveBeenCalled()
  })
  it('axios 实例已挂载拦截器（集成）', async () => {
    useAuthStore().setToken('jwt9')
    let seen
    const data = await request.get('/api/ping', {
      adapter: async (config) => { seen = config; return { data: { code: 0, msg: 'ok', data: 42 }, status: 200, headers: {}, config } }
    })
    expect(data).toBe(42)
    expect(seen.headers.Authorization).toBe('Bearer jwt9')
  })
})
