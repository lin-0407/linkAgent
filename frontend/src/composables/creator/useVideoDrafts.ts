import { computed, onBeforeUnmount, ref, watch, type Ref } from 'vue'
import {
  creationKey,
  readVideoDrafts,
  startVideoDraft,
  cancelVideoDraft,
} from '@/api/creationProduction'
import type { VideoDraftWorkspace, VideoDraftRequest } from '@/types/creationProduction'
import { errorText } from '@/utils/error'

export function useVideoDrafts(taskId: Ref<string>) {
  const workspace = ref<VideoDraftWorkspace | null>(null)
  const error = ref(''),
    busy = ref(false),
    loading = ref(false)
  const latest = computed(() => workspace.value?.versions[0] ?? null)
  const running = computed(
    () =>
      !!latest.value && !['COMPLETED', 'CANCELLED', 'WAITING_USER'].includes(latest.value.status),
  )
  let generation = 0,
    timer: ReturnType<typeof setTimeout> | undefined,
    reading: Promise<void> | null = null
  let pending: { task: string; input: VideoDraftRequest; key: string } | null = null
  const current = (task: string, token: number) => task === taskId.value && token === generation

  async function refresh() {
    const task = taskId.value,
      token = generation
    if (!task) return
    if (reading) return reading
    const request = (async () => {
      try {
        const result = await readVideoDrafts(task)
        if (current(task, token)) {
          workspace.value = result
          error.value = ''
        }
      } catch (reason) {
        if (current(task, token)) error.value = errorText(reason, '视频制作进度读取失败')
      }
    })()
    reading = request
    try {
      await request
    } finally {
      if (reading === request) reading = null
    }
  }
  async function poll(token: number) {
    await refresh()
    if (token === generation) timer = setTimeout(() => void poll(token), error.value ? 10000 : 2500)
  }
  async function start(input: VideoDraftRequest) {
    const task = taskId.value,
      token = generation
    if (!task || busy.value) return false
    busy.value = true
    error.value = ''
    try {
      // 网络结果不明时复用完整原请求，刷新也不能把同一次点击变成第二次付费制作。
      const storageKey = `video-draft-request:${task}`
      if (!pending) {
        try {
          pending = JSON.parse(sessionStorage.getItem(storageKey) || 'null')
        } catch {
          /* 本机缓存可能不可用。 */
        }
      }
      if (
        pending?.task === task &&
        workspace.value?.versions.some((v) => v.requestKey === pending?.key) &&
        pending.input.instruction === input.instruction
      ) {
        pending = null
        try {
          sessionStorage.removeItem(storageKey)
        } catch {
          /* 不影响已受理的制作。 */
        }
        return true
      }
      if (
        !pending ||
        pending.task !== task ||
        JSON.stringify(pending.input) !== JSON.stringify(input)
      ) {
        let saved: typeof pending = null
        try {
          saved = JSON.parse(sessionStorage.getItem(storageKey) || 'null')
        } catch {
          /* 本机缓存不可用时仍由服务端幂等保护。 */
        }
        pending =
          saved?.task === task && JSON.stringify(saved.input) === JSON.stringify(input)
            ? saved
            : { task, input: structuredClone(input), key: creationKey() }
        try {
          sessionStorage.setItem(storageKey, JSON.stringify(pending))
        } catch {
          /* 浏览器可能禁用存储。 */
        }
      }
      const accepted = await startVideoDraft(task, pending.input, pending.key)
      if (!current(task, token)) return false
      if (workspace.value)
        workspace.value = {
          ...workspace.value,
          versions: [
            accepted,
            ...workspace.value.versions.filter((v) => v.draftId !== accepted.draftId),
          ],
        }
      pending = null
      try {
        sessionStorage.removeItem(storageKey)
      } catch {
        /* 不影响已受理的制作。 */
      }
      await refresh()
      return true
    } catch (reason) {
      if (current(task, token)) {
        await refresh()
        if (pending && workspace.value?.versions.some((v) => v.requestKey === pending?.key)) {
          pending = null
          try {
            sessionStorage.removeItem(`video-draft-request:${task}`)
          } catch {
            /* 不影响已受理的制作。 */
          }
          return true
        }
        error.value = errorText(reason, '视频制作结果暂未确认，请重试；会复用同一次请求')
      }
      return false
    } finally {
      if (current(task, token)) busy.value = false
    }
  }
  async function cancel() {
    const task = taskId.value,
      draft = latest.value,
      token = generation
    if (!task || !draft || busy.value) return
    busy.value = true
    try {
      await cancelVideoDraft(task, draft.draftId)
      if (current(task, token)) await refresh()
    } catch (reason) {
      if (current(task, token)) error.value = errorText(reason, '暂时未能结束制作，请重试')
    } finally {
      if (current(task, token)) busy.value = false
    }
  }
  watch(
    taskId,
    async (task) => {
      generation++
      if (timer) clearTimeout(timer)
      reading = null
      workspace.value = null
      error.value = ''
      busy.value = false
      pending = null
      loading.value = false
      if (!task) return
      const token = generation
      loading.value = true
      await refresh()
      if (token === generation) {
        loading.value = false
        timer = setTimeout(() => void poll(token), 2500)
      }
    },
    { immediate: true, flush: 'sync' },
  )
  onBeforeUnmount(() => {
    generation++
    if (timer) clearTimeout(timer)
  })
  return { workspace, latest, running, error, busy, loading, refresh, start, cancel }
}
