export const DUP_CODE_MSG = '编码已存在（含已删除）'
export const REFERENCED_MSG = '该数据已被单据引用，不能删除，可改为停用'

/** 新增失败提示：编码重复统一提示含已删除 */
export function createErrorText(e) {
  const msg = e?.message || ''
  return msg.includes('编码已存在') ? DUP_CODE_MSG : msg || '保存失败'
}

/** 删除失败提示：409 表示被单据引用 */
export function deleteErrorText(e) {
  return e?.code === 409 ? REFERENCED_MSG : e?.message || '删除失败'
}
