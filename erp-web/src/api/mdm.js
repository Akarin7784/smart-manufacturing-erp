import request from '@/utils/request'

/** 主数据分页：GET /api/mdm/{resource}?page&size&keyword -> MyBatis-Plus Page {records,total,size,current,pages} */
export const pageMdm = (resource, params) => request.get(`/api/mdm/${resource}`, { params })
export const getMdm = (resource, id) => request.get(`/api/mdm/${resource}/${id}`)
export const createMdm = (resource, data) => request.post(`/api/mdm/${resource}`, data)
export const updateMdm = (resource, id, data) => request.put(`/api/mdm/${resource}/${id}`, data)
export const deleteMdm = (resource, id) => request.delete(`/api/mdm/${resource}/${id}`)
