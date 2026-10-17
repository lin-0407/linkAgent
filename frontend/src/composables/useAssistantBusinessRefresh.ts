import { onBeforeUnmount, onMounted } from 'vue'

export const ASSISTANT_BUSINESS_CHANGED = 'link-agent:assistant-business-changed'
export type AssistantBusinessChange = { operation: string; taskId?: string }

/** 多个步骤可能连续完成，合并刷新原页面，避免每步都重复拉取整页数据。 */
export function useAssistantBusinessRefresh(prefixes: string[], refresh: (changes: AssistantBusinessChange[]) => unknown) {
  let timer: ReturnType<typeof setTimeout> | undefined
  let pending: AssistantBusinessChange[] = []
  function changed(event: Event) {
    const value = (event as CustomEvent<AssistantBusinessChange>).detail
    if (!value || !prefixes.some(prefix => value.operation.startsWith(prefix))) return
    pending.push(value)
    clearTimeout(timer)
    timer = setTimeout(() => {
      const batch = pending; pending = []
      void Promise.resolve(refresh(batch)).catch(() => {})
    }, 350)
  }
  onMounted(() => window.addEventListener(ASSISTANT_BUSINESS_CHANGED, changed))
  onBeforeUnmount(() => { clearTimeout(timer); window.removeEventListener(ASSISTANT_BUSINESS_CHANGED, changed) })
}
