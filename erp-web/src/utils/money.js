import Decimal from 'decimal.js'

Decimal.set({ precision: 40, rounding: Decimal.ROUND_HALF_UP })

/** 去掉末尾 0 与多余小数点，与后端 Money.scaleError 一致 */
export function stripTrailingZeros(s) {
  if (s == null || s === '') return ''
  let t = String(s).trim()
  if (!t) return ''
  if (t.includes('.')) {
    t = t.replace(/0+$/, '').replace(/\.$/, '')
  }
  return t === '-0' ? '0' : t
}

/**
 * 校验数字字符串的整数位/小数位（去末尾 0 后计算）。
 * @returns {string|null} 错误文案，通过则 null
 */
export function validateDecimal(value, { integer, fraction, label, positive = false }) {
  const raw = value == null ? '' : String(value).trim()
  if (raw === '') return `${label}不能为空`
  if (!/^-?\d+(\.\d+)?$/.test(raw)) return `${label}必须是数字`
  const d = new Decimal(raw)
  if (positive && !d.gt(0)) return `${label}必须大于 0`
  if (!positive && d.lt(0)) return `${label}不能为负数`
  const stripped = stripTrailingZeros(d.abs().toFixed())
  const [intPart, fracPart = ''] = stripped.split('.')
  const intLen = (intPart.replace(/^0+/, '') || '0').length
  if (intLen > integer) return `${label}整数部分最多 ${integer} 位`
  if (fracPart.length > fraction) return `${label}最多 ${fraction} 位小数`
  return null
}

export function validateQty(value) {
  return validateDecimal(value, { integer: 14, fraction: 4, label: '数量', positive: true })
}

export function validatePrice(value) {
  return validateDecimal(value, { integer: 14, fraction: 4, label: '单价' })
}

export function validateAmount(value) {
  return validateDecimal(value, { integer: 16, fraction: 2, label: '金额' })
}

/** 行金额 = qty × price，HALF_UP 保留 2 位，返回普通字符串（无千分位） */
export function lineAmount(qty, price) {
  const q = new Decimal(qty || 0)
  const p = new Decimal(price || 0)
  return q.mul(p).toFixed(2)
}

/** 合计多行金额（已是 2 位字符串），返回普通字符串 */
export function sumAmounts(amounts) {
  let t = new Decimal(0)
  for (const a of amounts) t = t.add(new Decimal(a || 0))
  return t.toFixed(2)
}

/**
 * 展示用：不四舍五入、不补零；整数部分加千分位，小数部分原样。
 * "1234567890123456.12" -> "1,234,567,890,123,456.12"
 */
export function formatMoneyDisplay(v) {
  if (v == null || v === '') return '-'
  const s = String(v).trim()
  if (!s) return '-'
  const neg = s.startsWith('-')
  const body = neg ? s.slice(1) : s
  if (!/^\d+(\.\d+)?$/.test(body)) return s
  const [intPart, decPart] = body.split('.')
  const grouped = intPart.replace(/\B(?=(\d{3})+(?!\d))/g, ',')
  const out = decPart !== undefined ? `${grouped}.${decPart}` : grouped
  return neg ? `-${out}` : out
}

export function newIdempotencyKey() {
  if (typeof crypto !== 'undefined' && crypto.randomUUID) return crypto.randomUUID()
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0
    return (c === 'x' ? r : (r & 0x3) | 0x8).toString(16)
  })
}
