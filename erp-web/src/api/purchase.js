import request from '@/utils/request'

/** GET /api/purchase/orders?page&size&status -> Page {records,total,...}；records[].version */
export const pagePurchaseOrders = (params, config = {}) =>
  request.get('/api/purchase/orders', { ...config, params })

/** GET /api/purchase/orders/{id} -> {order, items}；order.version */
export const getPurchaseOrder = (id, config = {}) =>
  request.get(`/api/purchase/orders/${id}`, config)

/**
 * POST /api/purchase/orders
 * body: {supplierId, warehouseId, remark?, items:[{materialId, qty, price}]}
 * 可选 headers['Idempotency-Key']
 */
export const createPurchaseOrder = (data, config = {}) =>
  request.post('/api/purchase/orders', data, config)

/**
 * PUT /api/purchase/orders/{id}
 * body: {version, supplierId, warehouseId, remark?, items:[{materialId, qty, price}]}
 */
export const updatePurchaseOrder = (id, data, config = {}) =>
  request.put(`/api/purchase/orders/${id}`, data, config)

/** POST /api/purchase/orders/{id}/submit -> {approvalId} */
export const submitPurchaseOrder = (id, config = {}) =>
  request.post(`/api/purchase/orders/${id}/submit`, null, config)

/** POST /api/purchase/orders/{id}/receive -> detail（后端未支持 Idempotency-Key） */
export const receivePurchaseOrder = (id, config = {}) =>
  request.post(`/api/purchase/orders/${id}/receive`, null, config)
