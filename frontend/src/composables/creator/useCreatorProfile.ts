/**
 * 创作者画像状态管理 composable（P1-3）。
 * <p>
 * 封装画像的加载、刷新和派生状态，供 CreatorProfilePopover 组件使用。
 * <p>
 * 为什么独立为 composable 而非内联在 popover 组件中？
 * 画像数据可能被多个 UI 入口消费（顶部头像、设置面板等），
 * composable 可以按需共享状态，避免重复请求。
 * 当前阶段只有顶部头像一个消费者，但提前抽离保持可扩展性。
 */
import { computed, ref } from 'vue'
import { getBilibiliAccount, getCreatorProfile, refreshCreatorProfile } from '@/api/creator'
import { ApiError } from '@/api/http'
import type { BilibiliAccount, CreatorProfile } from '@/types/creator'

/** 默认用户标识（后续接入多用户时为动态值） */
const DEFAULT_USER_ID = 'default'

const profile = ref<CreatorProfile | null>(null)
const bilibiliAccount = ref<BilibiliAccount | null>(null)
const isLoading = ref(false)
const loadError = ref('')

/** 风格标签列表：从 JSON 数组字符串解析为 string[] */
const styleTagList = computed<string[]>(() => {
  if (!profile.value?.styleTags) return []
  try {
    const parsed = JSON.parse(profile.value.styleTags)
    return Array.isArray(parsed) ? parsed.filter((t): t is string => typeof t === 'string') : []
  } catch {
    return []
  }
})

/** 语气偏好文本 */
const toneGuide = computed(() => profile.value?.toneGuide ?? '')

/** 受众认知文本 */
const audienceView = computed(() => profile.value?.audienceView ?? '')

/** 是否有画像内容（有标签或有描述文字） */
const hasProfile = computed(() => {
  if (!profile.value) return false
  return styleTagList.value.length > 0 || !!toneGuide.value || !!audienceView.value
})

/** 画像最后更新时间 */
const lastUpdateTime = computed(() => profile.value?.updateTime ?? null)

/** 加载创作者画像 */
async function loadProfile(userId?: string) {
  isLoading.value = true
  loadError.value = ''
  try {
    const resolvedUserId = userId || DEFAULT_USER_ID
    const accountRequest = getBilibiliAccount(resolvedUserId).catch((error) => {
      if (error instanceof ApiError && error.status === 404) return null
      // B站身份只是画像的辅助信息，瞬时失败时保留旧头像，不能阻断画像正文读取。
      return bilibiliAccount.value
    })
    const [profileResult, accountResult] = await Promise.allSettled([
      getCreatorProfile(resolvedUserId),
      accountRequest,
    ])
    if (accountResult.status === 'fulfilled') {
      bilibiliAccount.value = accountResult.value
    }
    if (profileResult.status === 'rejected') {
      throw profileResult.reason
    }
    profile.value = profileResult.value
  } catch (err) {
    loadError.value = err instanceof Error ? err.message : String(err)
  } finally {
    isLoading.value = false
  }
}

/** 手动刷新画像（触发后端重新推理） */
async function refreshProfile(userId?: string) {
  isLoading.value = true
  loadError.value = ''
  try {
    // refreshCreatorProfile 触发后端重推理并返回最新画像
    profile.value = await refreshCreatorProfile(userId || DEFAULT_USER_ID)
  } catch (err) {
    loadError.value = err instanceof Error ? err.message : String(err)
  } finally {
    isLoading.value = false
  }
}

export function useCreatorProfile() {
  return {
    // 状态
    profile,
    bilibiliAccount,
    isLoading,
    loadError,
    // 派生
    styleTagList,
    toneGuide,
    audienceView,
    hasProfile,
    lastUpdateTime,
    // 方法
    loadProfile,
    refreshProfile,
  }
}
