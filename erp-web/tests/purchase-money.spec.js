import { formatMoneyDisplay, validateQty, validatePrice, validateAmount, lineAmount, sumAmounts, stripTrailingZeros, newIdempotencyKey } from '@/utils/money'

describe('金额展示：不舍入、不补零、只给整数部分加千分位', () => {
  it.each([
    ['1234567890123456.12', '1,234,567,890,123,456.12'],
    ['1234.5', '1,234.5'],
    ['1234.50', '1,234.50'],
    ['0.0001', '0.0001'],
    ['999', '999'],
    ['1000', '1,000'],
    ['12345678.123456', '12,345,678.123456'],
    ['-1234567.1', '-1,234,567.1'],
    ['0', '0']
  ])('%s -> %s', (v, out) => expect(formatMoneyDisplay(v)).toBe(out))
  it('空值显示 -，非数字原样', () => {
    expect(formatMoneyDisplay(null)).toBe('-')
    expect(formatMoneyDisplay('')).toBe('-')
    expect(formatMoneyDisplay('abc')).toBe('abc')
  })
})

describe('输入校验（去末尾 0 后计位）', () => {
  it('数量 14 整数 + 4 小数，必须 > 0', () => {
    expect(validateQty('99999999999999.9999')).toBeNull()
    expect(validateQty('1.23450000')).toBeNull()
    expect(validateQty('999999999999999')).toBe('数量整数部分最多 14 位')
    expect(validateQty('1.23456')).toBe('数量最多 4 位小数')
    expect(validateQty('0')).toBe('数量必须大于 0')
    expect(validateQty('')).toBe('数量不能为空')
    expect(validateQty('1.2.3')).toBe('数量必须是数字')
    expect(validateQty('abc')).toBe('数量必须是数字')
    expect(validateQty('1e3')).toBe('数量必须是数字')
  })
  it('单价可为 0，不能为负', () => {
    expect(validatePrice('0')).toBeNull()
    expect(validatePrice('0.0000')).toBeNull()
    expect(validatePrice('-1')).toBe('单价不能为负数')
    expect(validatePrice('1.00001')).toBe('单价最多 4 位小数')
    expect(validatePrice('000012345678901234')).toBeNull()
  })
  it('金额 16 整数 + 2 小数', () => {
    expect(validateAmount('9999999999999999.99')).toBeNull()
    expect(validateAmount('10000000000000000.00')).toBe('金额整数部分最多 16 位')
    expect(validateAmount('1.231')).toBe('金额最多 2 位小数')
    expect(validateAmount('1.230')).toBeNull()
  })
  it('stripTrailingZeros', () => {
    expect(stripTrailingZeros('1.2300')).toBe('1.23')
    expect(stripTrailingZeros('1.000')).toBe('1')
    expect(stripTrailingZeros('100')).toBe('100')
  })
})

describe('decimal.js 计算', () => {
  it('行金额 HALF_UP 保留 2 位，与后端一致', () => {
    expect(lineAmount('2.5', '3.333')).toBe('8.33')
    expect(lineAmount('3', '1.1')).toBe('3.30')
    expect(lineAmount('0.1', '0.2')).toBe('0.02')
    expect(lineAmount('1', '0.005')).toBe('0.01')
    expect(lineAmount('99999999999999', '99')).toBe('9899999999999901.00')
  })
  it('合计不丢精度', () => {
    expect(sumAmounts(['8.33', '3.30'])).toBe('11.63')
    expect(sumAmounts(['9999999999999999.99', '0.01'])).toBe('10000000000000000.00')
    expect(sumAmounts(['0.10', '0.20'])).toBe('0.30')
  })
  it('UUID 幂等键', () => {
    const a = newIdempotencyKey(), b = newIdempotencyKey()
    expect(a).toMatch(/^[0-9a-f-]{36}$/)
    expect(a).not.toBe(b)
  })
})
