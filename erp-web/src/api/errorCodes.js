/**
 * 后端业务错误码（响应体 code）。HTTP 状态码表示类别，子码细分原因；
 * 其余情况 code 与 HTTP 状态码相同。
 */
export const ErrorCode = Object.freeze({
  OK: 0,
  DUPLICATE_CODE: 40001, // HTTP 400 编码重复（含已删除）
  CONCURRENT_MODIFIED: 40900, // HTTP 409 乐观锁/并发冲突
  REFERENCED: 40901, // HTTP 409 被单据引用，不能删除
  DUPLICATE_REQUEST: 40902 // HTTP 409 同一请求 ID 参数不同的重复提交
})

export const ERROR_MESSAGES = Object.freeze({
  [ErrorCode.DUPLICATE_CODE]: '编码已存在（含已删除）',
  [ErrorCode.CONCURRENT_MODIFIED]: '数据已被修改，请刷新重试',
  [ErrorCode.REFERENCED]: '该数据已被单据引用，不能删除，可改为停用',
  [ErrorCode.DUPLICATE_REQUEST]: '请勿重复提交'
})

/** 子码 -> HTTP 类别（40001 -> 400）；普通码原样返回 */
export function categoryOf(code) {
  return code >= 10000 ? Math.floor(code / 100) : code
}
