import { createApp } from 'vue'
import { createPinia } from 'pinia'
import piniaPluginPersistedstate from 'pinia-plugin-persistedstate'
import App from './App.vue'
import router from './router'
import './styles/theme.css'

const app = createApp(App)

// Pinia 状态管理层，persistedstate 插件自动将标记的 store 字段持久化到 localStorage
const pinia = createPinia()
pinia.use(piniaPluginPersistedstate)
app.use(pinia)

app.use(router)
app.mount('#app')
