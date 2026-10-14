export const PO_STATUS = Object.freeze({
  DRAFT: { text: '草稿', tag: 'info' },
  PENDING: { text: '审批中', tag: 'warning' },
  APPROVED: { text: '已审批', tag: 'success' },
  RECEIVED: { text: '已收货', tag: '' },
  REJECTED: { text: '已驳回', tag: 'danger' }
})
export const PO_BIZ_TYPE = 'PURCHASE_ORDER'
export const PO_MSG = Object.freeze({
  maybeSubmitted: '可能已提交，请先刷新列表确认',
  docNoBusy: '取号繁忙，请稍后重试',
  conflict: '数据已被修改，请刷新后重试'
})
