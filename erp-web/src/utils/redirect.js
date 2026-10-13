/** 只允许站内路径：以 '/' 开头且不以 '//' 开头（防开放重定向），否则回首页 */
export function safeRedirect(r) {
  if (typeof r !== 'string' || !r.startsWith('/') || r.startsWith('//') || r.startsWith('/\\')) return '/'
  return r
}
