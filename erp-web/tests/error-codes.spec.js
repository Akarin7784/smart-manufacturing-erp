import { setActivePinia, createPinia } from 'pinia'
import { ElMessage } from 'element-plus'
import { onFulfilled, onRejected, setLoginRedirect, BizError, MSG } from '@/utils/request'
import { ErrorCode, ERROR_MESSAGES, categoryOf } from '@/api/errorCodes'

const httpErr = (status, body, config = { url: '/api/mdm/materials/1' }) => ({ config, response: { status, data: body } })
const ok200 = (body, config = { url: '/api/x' }) => ({ data: body, config })

describe('业务错误码', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    setLoginRedirect(vi.fn())
    vi.spyOn(ElMessage, 'error').mockImplementation(() => {})
    ElMessage.error.mockClear()
  })

  it('常量与后端约定一致', () => {
    expect(ErrorCode).toMatchObject({ DUPLICATE_CODE: 40001, CONCURRENT_MODIFIED: 40900, REFERENCED: 40901, DUPLICATE_REQUEST: 40902 })
    expect(Object.isFrozen(ErrorCode)).toBe(true)
    expect(MSG.conflict).toBe('数据已被修改，请刷新重试')
  })
  it.each([[40001, 400], [40900, 409], [40901, 409], [40902, 409], [403, 403], [0, 0]])('categoryOf(%i)=%i', (c, s) => expect(categoryOf(c)).toBe(s))

  it.each([
    [400, 40001, '编码已存在（含已删除）'],
    [409, 40900, '数据已被修改，请刷新重试'],
    [409, 40901, '该数据已被单据引用，不能删除，可改为停用'],
    [409, 40902, '请勿重复提交']
  ])('HTTP %i + code %i -> %s，错误对象暴露子码与类别', async (status, code, text) => {
    const e = await onRejected(httpErr(status, { code, msg: '后端原文不参与判断', data: null })).catch((x) => x)
    expect(e).toBeInstanceOf(BizError)
    expect(e.code).toBe(code)
    expect(e.status).toBe(status)
    expect(e.message).toBe(text)
    expect(ElMessage.error).toHaveBeenCalledTimes(1)
    expect(ElMessage.error).toHaveBeenCalledWith(text)
  })
  it('只看 code 不看 msg：msg 含“编码已存在”但 code=400 不映射', async () => {
    const e = await onRejected(httpErr(400, { code: 400, msg: '编码已存在: M1' })).catch((x) => x)
    expect(e.message).toBe('编码已存在: M1')
    expect(e.code).toBe(400)
  })
  it('409 无子码 -> 通用刷新重试', async () => {
    const e = await onRejected(httpErr(409, null)).catch((x) => x)
    expect(e).toMatchObject({ code: 409, status: 409, message: MSG.conflict })
  })
  it('body.code 为 0 或缺失时以 HTTP 状态为 code', async () => {
    const e = await onRejected(httpErr(404, { code: 0, msg: '不存在' })).catch((x) => x)
    expect(e).toMatchObject({ code: 404, status: 404, message: '不存在' })
  })
  it('兼容 HTTP 200 + 子码', async () => {
    const e = await onFulfilled(ok200({ code: 40901, msg: 'x' })).catch((x) => x)
    expect(e).toMatchObject({ code: 40901, status: 409, message: ERROR_MESSAGES[40901] })
  })
  it('silent 下不弹提示但仍返回子码', async () => {
    const e = await onRejected(httpErr(409, { code: 40900 }, { url: '/a', silent: true })).catch((x) => x)
    expect(e.code).toBe(40900)
    expect(ElMessage.error).not.toHaveBeenCalled()
  })
  it('登录接口 403 显示后端原因（如账号已停用）', async () => {
    const e = await onFulfilled(ok200({ code: 403, msg: '账号已停用' }, { url: '/api/auth/login' })).catch((x) => x)
    expect(e.message).toBe('账号已停用')
  })
})
