const status = { prop: 'status', label: '状态', type: 'status', width: 90 }
const created = { prop: 'createdAt', label: '创建时间', width: 180 }

export const MDM_RESOURCES = {
  materials: {
    title: '物料',
    columns: [
      { prop: 'code', label: '物料编码', width: 140 },
      { prop: 'name', label: '物料名称' },
      { prop: 'spec', label: '规格' },
      { prop: 'unit', label: '单位', width: 80 },
      { prop: 'category', label: '分类' },
      status,
      created
    ]
  },
  customers: {
    title: '客户',
    columns: [
      { prop: 'code', label: '客户编码', width: 140 },
      { prop: 'name', label: '客户名称' },
      { prop: 'contact', label: '联系人' },
      { prop: 'phone', label: '电话' },
      status,
      created
    ]
  },
  suppliers: {
    title: '供应商',
    columns: [
      { prop: 'code', label: '供应商编码', width: 140 },
      { prop: 'name', label: '供应商名称' },
      { prop: 'contact', label: '联系人' },
      { prop: 'phone', label: '电话' },
      status,
      created
    ]
  },
  warehouses: {
    title: '仓库',
    columns: [
      { prop: 'code', label: '仓库编码', width: 140 },
      { prop: 'name', label: '仓库名称' },
      { prop: 'address', label: '地址' },
      status,
      created
    ]
  }
}
