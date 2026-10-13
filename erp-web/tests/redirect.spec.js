import { safeRedirect } from '@/utils/redirect'
describe('safeRedirect', () => {
  it.each([
    ['/sales', '/sales'],
    ['/mdm/materials?page=2', '/mdm/materials?page=2'],
    ['//evil.com', '/'],
    ['/\\evil.com', '/'],
    ['https://evil.com', '/'],
    ['javascript:alert(1)', '/'],
    ['sales', '/'],
    ['', '/'],
    [undefined, '/'],
    [['/a'], '/']
  ])('%s -> %s', (i, o) => expect(safeRedirect(i)).toBe(o))
})
