/** LocalDateTime 字符串（2026-10-14T03:24:49.088012）-> 2026-10-14 03:24:49 */
export function formatDateTime(v) {
  if (!v) return '-'
  const m = String(v).match(/^(\d{4}-\d{2}-\d{2})[T ](\d{2}:\d{2}:\d{2})/)
  return m ? `${m[1]} ${m[2]}` : String(v)
}
