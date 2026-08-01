import { computed, reactive, ref } from 'vue'
import { getPrePublishSettings, savePrePublishSettings } from '@/api/creator'
import type { CreatorPreferenceMode, PrePublishSettings } from '@/types/creator'

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
  const submittedPrePublishTaskGuidance = ref(prePublishTaskGuidanceSnapshot())
  const submittedFeedbackTaskGuidance = ref(feedbackTaskGuidanceSnapshot())
  const activeSettingsTaskId = ref('')
  const isLoadingPrePublishSettings = ref(false)
  const isSavingPrePublishSettings = ref(false)
  const prePublishSettingsSaveState = ref<'idle' | 'saved' | 'error'>('idle')
  const prePublishSettingsError = ref('')
  const prePublishSettingsErrorSource = ref<'load' | 'save' | null>(null)
  let settingsLoadVersion = 0
  const hasPrePublishSettingsChanges = computed(
    () => prePublishTaskGuidanceSnapshot() !== submittedPrePublishTaskGuidance.value,
  )
  const hasTaskGuidanceInput = computed(() =>
    hasPrePublishSettingsChanges.value ||
      feedbackTaskGuidanceSnapshot() !== submittedFeedbackTaskGuidance.value,
  )

  // ── 初始化 ──

  /** 只从 localStorage 恢复反馈指导词，发布前设置改由任务级接口恢复。 */
  function loadGuidanceSettings() {
    localStorage.removeItem(legacyPromptStorageKey)
    try {
      const savedValue = localStorage.getItem(guidanceStorageKey)
      if (!savedValue) return
      const saved = JSON.parse(savedValue) as {
        feedbackGuidance?: string
      }
      if (saved.feedbackGuidance) feedbackAnalyzeForm.customGuidance = saved.feedbackGuidance
    } catch {
      localStorage.removeItem(guidanceStorageKey)
    }
  }

  /** 反馈分析指导词仍是本机通用设置，发布前设置不能在这里跨任务复用。 */
  function persistGuidanceSettings() {
    localStorage.setItem(
      guidanceStorageKey,
      JSON.stringify({
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

  function markPrePublishGuidanceSubmitted() {
    submittedPrePublishTaskGuidance.value = prePublishTaskGuidanceSnapshot()
  }

  function markFeedbackGuidanceSubmitted() {
    submittedFeedbackTaskGuidance.value = feedbackTaskGuidanceSnapshot()
  }

  /** 切换任务时清空发布前任务字段，随后再由任务级接口恢复对应内容。 */
  function resetTaskGuidanceFields() {
    settingsLoadVersion += 1
    prePublishForm.customGuidance = ''
    prePublishForm.creatorPreference = ''
    prePublishForm.titleStyle = ''
    prePublishForm.extraRequirement = ''
    feedbackAnalyzeForm.analysisFocus = ''
    feedbackAnalyzeForm.extraRequirement = ''
    resetPrePublishPreferenceMode()
    activeSettingsTaskId.value = ''
    isLoadingPrePublishSettings.value = false
    isSavingPrePublishSettings.value = false
    prePublishSettingsError.value = ''
    prePublishSettingsErrorSource.value = null
    prePublishSettingsSaveState.value = 'idle'
    markPrePublishGuidanceSubmitted()
    markFeedbackGuidanceSubmitted()
  }

  async function loadTaskPrePublishSettings(taskId: string) {
    const normalizedTaskId = taskId.trim()
    const version = ++settingsLoadVersion
    activeSettingsTaskId.value = normalizedTaskId
    isLoadingPrePublishSettings.value = true
    prePublishSettingsError.value = ''
    prePublishSettingsErrorSource.value = null
    prePublishSettingsSaveState.value = 'idle'
    try {
      const settings = await getPrePublishSettings(normalizedTaskId)
      if (version !== settingsLoadVersion || activeSettingsTaskId.value !== normalizedTaskId) {
        return false
      }
      applyPrePublishSettings(settings)
      submittedPrePublishTaskGuidance.value = prePublishTaskGuidanceSnapshot()
      prePublishSettingsSaveState.value = settings.updateTime ? 'saved' : 'idle'
      return true
    } catch (error) {
      if (version === settingsLoadVersion && activeSettingsTaskId.value === normalizedTaskId) {
        prePublishSettingsError.value = error instanceof Error ? error.message : '发布前设置读取失败'
        prePublishSettingsErrorSource.value = 'load'
        prePublishSettingsSaveState.value = 'error'
      }
      return false
    } finally {
      if (version === settingsLoadVersion) isLoadingPrePublishSettings.value = false
    }
  }

  async function saveTaskPrePublishSettings(taskId: string, force = false) {
    const normalizedTaskId = taskId.trim()
    if (!normalizedTaskId) {
      prePublishSettingsError.value = '请先选择创作任务，再保存本次设置。'
      prePublishSettingsErrorSource.value = 'save'
      prePublishSettingsSaveState.value = 'error'
      return false
    }
    if (!force && !hasPrePublishSettingsChanges.value) return true

    const version = ++settingsLoadVersion
    activeSettingsTaskId.value = normalizedTaskId
    isSavingPrePublishSettings.value = true
    prePublishSettingsError.value = ''
    prePublishSettingsErrorSource.value = null
    prePublishSettingsSaveState.value = 'idle'
    try {
      const settings = await savePrePublishSettings(normalizedTaskId, {
        preferenceMode: prePublishForm.preferenceMode,
        creatorPreference: prePublishForm.creatorPreference,
        titleStyle: prePublishForm.titleStyle,
        extraRequirement: prePublishForm.extraRequirement,
        customGuidance: prePublishForm.customGuidance,
      })
      if (version !== settingsLoadVersion || activeSettingsTaskId.value !== normalizedTaskId) {
        return false
      }
      applyPrePublishSettings(settings)
      submittedPrePublishTaskGuidance.value = prePublishTaskGuidanceSnapshot()
      prePublishSettingsSaveState.value = 'saved'
      return true
    } catch (error) {
      if (version === settingsLoadVersion && activeSettingsTaskId.value === normalizedTaskId) {
        prePublishSettingsError.value = error instanceof Error ? error.message : '发布前设置保存失败'
        prePublishSettingsErrorSource.value = 'save'
        prePublishSettingsSaveState.value = 'error'
      }
      return false
    } finally {
      if (version === settingsLoadVersion && activeSettingsTaskId.value === normalizedTaskId) {
        isSavingPrePublishSettings.value = false
      }
    }
  }

  function applyPrePublishSettings(settings: PrePublishSettings) {
    prePublishForm.preferenceMode = settings.preferenceMode
    prePublishForm.creatorPreference = settings.creatorPreference
    prePublishForm.titleStyle = settings.titleStyle
    prePublishForm.extraRequirement = settings.extraRequirement
    prePublishForm.customGuidance = settings.customGuidance
  }

  return {
    prePublishForm,
    feedbackAnalyzeForm,
    guidanceEditorTarget,
    lastPrePublishPreferenceMode,
    hasPrePublishPreferenceModeSnapshot,
    hasTaskGuidanceInput,
    hasPrePublishSettingsChanges,
    isLoadingPrePublishSettings,
    isSavingPrePublishSettings,
    prePublishSettingsSaveState,
    prePublishSettingsError,
    prePublishSettingsErrorSource,
    defaultPrePublishGuidance,
    defaultFeedbackGuidance,
    loadGuidanceSettings,
    openGuidanceEditor,
    closeGuidanceEditor,
    resetCurrentGuidance,
    resetPrePublishPreferenceMode,
    markPrePublishGuidanceSubmitted,
    markFeedbackGuidanceSubmitted,
    resetTaskGuidanceFields,
    loadTaskPrePublishSettings,
    saveTaskPrePublishSettings,
  }

  function prePublishTaskGuidanceSnapshot() {
    return JSON.stringify({
      creatorPreference: prePublishForm.creatorPreference,
      titleStyle: prePublishForm.titleStyle,
      extraRequirement: prePublishForm.extraRequirement,
      customGuidance: prePublishForm.customGuidance,
      preferenceMode: prePublishForm.preferenceMode,
    })
  }

  function feedbackTaskGuidanceSnapshot() {
    return JSON.stringify({
      analysisFocus: feedbackAnalyzeForm.analysisFocus,
      extraRequirement: feedbackAnalyzeForm.extraRequirement,
    })
  }
}
