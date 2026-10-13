import { createApp } from 'vue'
import { createPinia } from 'pinia'
// 通过 JS 调用的 Element Plus 服务组件，需手动引入样式
import 'element-plus/es/components/message/style/css'
import App from './App.vue'
import router from './router'

const app = createApp(App)
app.use(createPinia())
app.use(router)
app.mount('#app')
