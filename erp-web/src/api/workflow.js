import request from '@/utils/request'

/** GET /api/workflow/approvals/todo -> ApprovalInstance[] */
export const listApprovalTodo = (config = {}) =>
  request.get('/api/workflow/approvals/todo', config)

/** GET /api/workflow/approvals?bizType&bizId */
export const listApprovals = (params, config = {}) =>
  request.get('/api/workflow/approvals', { ...config, params })

/** POST /api/workflow/approvals/{id}/decide {approved, comment?} */
export const decideApproval = (id, data, config = {}) =>
  request.post(`/api/workflow/approvals/${id}/decide`, data, config)
