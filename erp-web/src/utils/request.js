import axios from 'axios'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '@/stores/auth'

export const MSG = {
  unauthorized: '登录已过期，请重新登录',
  forbidden: '没有权限执行此操作',
  conflict: '数据已被他人修改，请刷新后重试',
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

/** 按状态码统一处理；返回提示文案 */
export function handleStatus(status, config, msg) {
  if (status === 401) {
    if (isLoginRequest(config)) return msg || '用户名或密码错误'
    useAuthStore().logout()
    redirectToLogin()
    return MSG.unauthorized
  }
  if (status === 403) return msg && msg !== '无权限' ? msg : MSG.forbidden
  if (status === 409) return MSG.conflict
  if (status === 404) return msg || MSG.notFound
  if (status >= 500) return MSG.server
  return msg || MSG.network
}

export class BizError extends Error {
  constructor(code, msg) {
    super(msg)
    this.code = code
  }
}

function fail(code, config, msg) {
  const text = handleStatus(code, config, msg)
  if (!config?.silent) ElMessage.error(text)
  return Promise.reject(new BizError(code, text))
}

/**
 * 成功/失败以 HTTP 状态码为准（非 2xx 走 onRejected，读取 error.response.status）。
 * 兼容旧后端：HTTP 200 但 body.code≠0 时按 body.code 视为失败。
 */
export function onFulfilled(response) {
  const body = response.data
  if (body && typeof body === 'object' && 'code' in body) {
    if (body.code === 0) return body.data
    return fail(body.code, response.config, body.msg)
  }
  return body
}

export function onRejected(error) {
  const status = error.response?.status
  if (!status) return fail(0, error.config, MSG.network)
  const body = error.response.data
  return fail(status, error.config, body && typeof body === 'object' ? body.msg : undefined)
}

service.interceptors.request.use(attachToken)
service.interceptors.response.use(onFulfilled, onRejected)

export default service
