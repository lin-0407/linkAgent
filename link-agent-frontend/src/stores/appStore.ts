import { defineStore } from 'pinia'
import { ref } from 'vue'

/**
 * 全局应用级状态：横跨所有页面和浮窗的设置、开发者模式开关。
 * 替代原来散落在 App.vue 的 ref + localStorage 直写。
 */
export const useAppStore = defineStore(
  'app',
  () => {
    const settingsOpen = ref(false)
    const developerMode = ref(false)

    return { settingsOpen, developerMode }
  },
  {
    persist: {
      key: 'link-agent-app',
      // 仅持久化 developerMode；settingsOpen 不需要跨会话保留
      pick: ['developerMode'],
    },
  },
)
