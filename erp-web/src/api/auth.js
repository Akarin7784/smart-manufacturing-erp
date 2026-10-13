import request from '@/utils/request'

/** POST /api/auth/login  {username,password} -> {token} */
export const login = (data) => request.post('/api/auth/login', data)
/** GET /api/auth/me -> {username, authorities[]} */
export const me = () => request.get('/api/auth/me')
