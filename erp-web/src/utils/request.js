import axios from 'axios'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { ErrorCode, ERROR_MESSAGES, categoryOf } from '@/api/errorCodes'

export const MSG = {
  unauthorized: '登录已过期，请重新登录',
  forbidden: '没有权限执行此操作',
  conflict: ERROR_MESSAGES[40900],
  notFound: '请求的数据不存在',
  network: '网络异常，请稍后重试',
  server: '系统异常，请稍后重试'
}

let redirectToLogin = () => {
  window.location.href = '/login'
}
/** 由 router 注入跳转函数，避免循环依赖 */
export function setLoginRedirect(fn) {
  redirectToLogin = fn
}

const service = axios.create({ baseURL: '/', timeout: 15000 })

export function attachToken(config) {
  const auth = useAuthStore()
  if (auth.token) {
    config.headers = config.headers || {}
    config.headers.Authorization = `Bearer ${auth.token}`
  }
  return config
}

function isLoginRequest(config) {
  return (config?.url || '').includes('/api/auth/login')
}

/**
 * 根据 HTTP 类别 status 与业务码 code 决定提示文案（只看码，不匹配 msg 文本）。
 * 401 会清 token 并跳登录（登录接口本身除外）。
 */
export function resolveError(status, code, config, msg) {
  if (ERROR_MESSAGES[code]) return ERROR_MESSAGES[code]
  if (status === 401) {
    if (isLoginRequest(config)) return msg || '用户名或密码错误'
    useAuthStore().logout()
    redirectToLogin()
    return MSG.unauthorized
  }
  if (status === 403) return isLoginRequest(config) && msg ? msg : MSG.forbidden
  if (status === 409) return MSG.conflict
  if (status === 404) return msg || MSG.notFound
  if (status >= 500) return MSG.server
  return msg || MSG.network
}

/** 兼容旧调用：仅按状态码 */
export const handleStatus = (status, config, msg) => resolveError(status, status, config, msg)

/** code：业务码（子码，如 40001）；status：HTTP 类别（如 400） */
export class BizError extends Error {
  constructor(code, msg, status = categoryOf(code)) {
    super(msg)
    this.code = code
    this.status = status
  }
}

function fail(status, code, config, msg) {
  const text = resolveError(status, code, config, msg)
  if (!config?.silent) ElMessage.error(text)
  return Promise.reject(new BizError(code, text, status))
}

/**
 * 成功/失败以 HTTP 状态码为准（非 2xx 走 onRejected）；业务子码取 body.code。
 * 兼容旧后端：HTTP 200 但 body.code≠0 时视为失败，类别由 code 推导（40001 -> 400）。
 */
export const CANCELED = 'ERR_CANCELED'

export function onFulfilled(response) {
  const body = response.data
  if (body && typeof body === 'object' && 'code' in body) {
    if (body.code === ErrorCode.OK) return body.data
    return fail(categoryOf(body.code), body.code, response.config, body.msg)
  }
  return body
}

export function onRejected(error) {
  // 被 AbortController 取消的请求：静默拒绝，不提示、不当作网络错误
  if (axios.isCancel(error)) {
    const e = new BizError(CANCELED, '请求已取消', 0)
    e.canceled = true
    return Promise.reject(e)
  }
  const status = error.response?.status
  if (!status) return fail(0, 0, error.config, MSG.network)
  const body = error.response.data
  const isObj = body && typeof body === 'object'
  const code = isObj && typeof body.code === 'number' && body.code !== 0 ? body.code : status
  return fail(status, code, error.config, isObj ? body.msg : undefined)
}

service.interceptors.request.use(attachToken)
service.interceptors.response.use(onFulfilled, onRejected)

export default service
