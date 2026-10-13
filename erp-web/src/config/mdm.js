const status = { prop: 'status', label: '状态', type: 'status', width: 90 }
const created = { prop: 'createdAt', label: '创建时间', type: 'datetime', width: 180 }

// 表单字段与 schema.sql 对齐：code ≤64 必填、name ≤128 必填，其余可选
const code = (label) => ({ prop: 'code', label, required: true, max: 64, createOnly: true })
const name = (label) => ({ prop: 'name', label, required: true, max: 128 })
const contactFields = [{ prop: 'contact', label: '联系人', max: 64 }, { prop: 'phone', label: '电话', max: 32 }]

export const MDM_FORMS = {
  materials: [code('物料编码'), name('物料名称'), { prop: 'spec', label: '规格', max: 128 }, { prop: 'unit', label: '单位', max: 16 }, { prop: 'category', label: '分类', max: 64 }],
  customers: [code('客户编码'), name('客户名称'), ...contactFields],
  suppliers: [code('供应商编码'), name('供应商名称'), ...contactFields],
  warehouses: [code('仓库编码'), name('仓库名称'), { prop: 'address', label: '地址', max: 255 }]
}

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
