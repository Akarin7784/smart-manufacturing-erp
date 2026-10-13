import { formatDateTime } from '@/utils/format'
it('formatDateTime', () => {
  expect(formatDateTime('2026-10-14T03:24:49.088012')).toBe('2026-10-14 03:24:49')
  expect(formatDateTime(null)).toBe('-')
  expect(formatDateTime('abc')).toBe('abc')
})
