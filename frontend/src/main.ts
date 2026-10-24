import { createApp } from 'vue'
import { createPinia } from 'pinia'
import piniaPluginPersistedstate from 'pinia-plugin-persistedstate'
import App from './App.vue'
import router from './router'
import './styles/theme.css'
import './styles/workbench.css'

const app = createApp(App)

/**
 * 统一收集组件渲染 / 生命周期异常。
 *
 * 没有这个钩子时，Vue 的默认行为是卸载整棵组件树 —— 用户看到白屏，
 * 控制台之外没有任何线索。这里先把现场打出来（组件名 + 生命周期 + 堆栈），
 * 后续接入上报时只需替换 console.error 这一处。
 */
app.config.errorHandler = (error, instance, info) => {
  console.error('[vue-error]', info, instance?.$options?.name ?? 'anonymous', error)
}

// 未被 await 的 Promise 拒绝同样要留痕：上传、SSE、批量刷新都可能走到这里
window.addEventListener('unhandledrejection', (event) => {
  console.error('[unhandled-rejection]', event.reason)
})

// Pinia 状态管理层，persistedstate 插件自动将标记的 store 字段持久化到 localStorage
const pinia = createPinia()
pinia.use(piniaPluginPersistedstate)
app.use(pinia)

app.use(router)
app.mount('#app')
