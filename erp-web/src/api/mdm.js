import request from '@/utils/request'

/** 主数据分页：GET /api/mdm/{resource}?page&size&keyword -> MyBatis-Plus Page {records,total,size,current,pages} */
export const pageMdm = (resource, params) => request.get(`/api/mdm/${resource}`, { params })
export const getMdm = (resource, id) => request.get(`/api/mdm/${resource}/${id}`)
