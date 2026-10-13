import { defineStore } from 'pinia'

export const TOKEN_KEY = 'erp_token'
export const USER_KEY = 'erp_user'

function readUser() {
  try {
    return JSON.parse(localStorage.getItem(USER_KEY) || 'null')
  } catch {
    return null
  }
}

export const useAuthStore = defineStore('auth', {
  state: () => ({
    token: localStorage.getItem(TOKEN_KEY) || '',
    user: readUser()
  }),
  getters: {
    isLoggedIn: (s) => !!s.token,
    username: (s) => s.user?.username || '',
    authorities: (s) => s.user?.authorities || []
  },
  actions: {
    setToken(token) {
      this.token = token || ''
      if (token) localStorage.setItem(TOKEN_KEY, token)
      else localStorage.removeItem(TOKEN_KEY)
    },
    setUser(user) {
      this.user = user || null
      if (user) localStorage.setItem(USER_KEY, JSON.stringify(user))
      else localStorage.removeItem(USER_KEY)
    },
    hasAuthority(a) {
      return this.authorities.includes(a)
    },
    logout() {
      this.setToken('')
      this.setUser(null)
    }
  }
})
