import { reactive, ref } from 'vue'
import type { CreatorPreferenceMode } from '@/types/creator'

type GuidanceEditorTarget = 'prePublish' | 'feedback'

// 默认指导词内容
const defaultPrePublishGuidance =
  '标题表达克制、具体，避免宏大叙事；简介说清楚视频在讲什么，带上与内容直接相关的关键词。'
const defaultFeedbackGuidance =
  '先归纳观众最关注的问题，再分析争议点和误解点，最后给可操作的下期改进建议。'

const guidanceStorageKey = 'link-agent-creator-guidance'
const legacyPromptStorageKey = 'link-agent-creator-system-prompts'

export function useCreatorGuidance() {
  // ── 表单 ──
  const prePublishForm = reactive({
    customGuidance: '',
    creatorPreference: '',
    titleStyle: '',
    extraRequirement: '',
    preferenceMode: 'USE_HISTORY' as CreatorPreferenceMode,
  })

  const feedbackAnalyzeForm = reactive({
    customGuidance: '',
    analysisFocus: '',
    extraRequirement: '',
  })

  // ── UI 状态 ──
  const guidanceEditorTarget = ref<GuidanceEditorTarget | null>(null)
  const lastPrePublishPreferenceMode = ref<CreatorPreferenceMode>('USE_HISTORY')
  const hasPrePublishPreferenceModeSnapshot = ref(false)

  // ── 初始化 ──

  /** 从 localStorage 恢复指导词设置 */
  function loadGuidanceSettings() {
    localStorage.removeItem(legacyPromptStorageKey)
    try {
      const savedValue = localStorage.getItem(guidanceStorageKey)
      if (!savedValue) return
      const saved = JSON.parse(savedValue) as {
        prePublishGuidance?: string
        feedbackGuidance?: string
      }
      if (saved.prePublishGuidance) prePublishForm.customGuidance = saved.prePublishGuidance
      if (saved.feedbackGuidance) feedbackAnalyzeForm.customGuidance = saved.feedbackGuidance
    } catch {
      localStorage.removeItem(guidanceStorageKey)
    }
  }

  /** 持久化指导词到 localStorage */
  function persistGuidanceSettings() {
    localStorage.setItem(
      guidanceStorageKey,
      JSON.stringify({
        prePublishGuidance: prePublishForm.customGuidance,
        feedbackGuidance: feedbackAnalyzeForm.customGuidance,
      }),
    )
  }

  // ── 操作 ──

  function openGuidanceEditor(target: GuidanceEditorTarget) {
    guidanceEditorTarget.value = target
  }

  function closeGuidanceEditor() {
    persistGuidanceSettings()
    guidanceEditorTarget.value = null
  }

  function resetCurrentGuidance() {
    if (guidanceEditorTarget.value === 'prePublish') {
      prePublishForm.customGuidance = defaultPrePublishGuidance
    }
    if (guidanceEditorTarget.value === 'feedback') {
      feedbackAnalyzeForm.customGuidance = defaultFeedbackGuidance
    }
  }

  function resetPrePublishPreferenceMode() {
    prePublishForm.preferenceMode = 'USE_HISTORY'
    lastPrePublishPreferenceMode.value = 'USE_HISTORY'
    hasPrePublishPreferenceModeSnapshot.value = false
  }

  return {
    prePublishForm,
    feedbackAnalyzeForm,
    guidanceEditorTarget,
    lastPrePublishPreferenceMode,
    hasPrePublishPreferenceModeSnapshot,
    defaultPrePublishGuidance,
    defaultFeedbackGuidance,
    loadGuidanceSettings,
    openGuidanceEditor,
    closeGuidanceEditor,
    resetCurrentGuidance,
    resetPrePublishPreferenceMode,
  }
}
