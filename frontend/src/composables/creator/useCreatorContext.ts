import { ref } from 'vue'
import type { Ref } from 'vue'
import {
  disableCreatorContextTerm,
  listCreatorContextTerms,
  listCreatorPreferences,
  recordCreatorContextTermFeedback,
  saveCreatorContextTerm as apiSaveContextTerm,
} from '@/api/creator'
import type {
  CreatorContextTerm,
  CreatorContextTermPayload,
  CreatorPreference,
} from '@/types/creator'

const hasText = (v: string) => v.trim().length > 0

export function useCreatorContext(errorRef: Ref<string>) {
  // ── 状态 ──
  const creatorPreferences = ref<CreatorPreference[]>([])
  const creatorContextTerms = ref<CreatorContextTerm[]>([])
  const isLoadingCreatorPreferences = ref(false)
  const isLoadingCreatorContextTerms = ref(false)
  const isSavingCreatorContextTerm = ref(false)
  const savingContextTermKey = ref('')

  // ── 方法 ──
  function showError(error: unknown) {
    errorRef.value = error instanceof Error ? error.message : String(error)
  }

  async function loadCreatorPreferences(userId = 'default') {
    isLoadingCreatorPreferences.value = true
    try {
      creatorPreferences.value = await listCreatorPreferences(userId)
    } catch (error) {
      showError(error)
    } finally {
      isLoadingCreatorPreferences.value = false
    }
  }

  async function loadCreatorContextTerms(userId = 'default', videoType?: string) {
    isLoadingCreatorContextTerms.value = true
    try {
      creatorContextTerms.value = await listCreatorContextTerms(
        userId,
        videoType,
        false,
        50,
      )
    } catch (error) {
      showError(error)
    } finally {
      isLoadingCreatorContextTerms.value = false
    }
  }

  async function saveContextTerm(payload: Omit<CreatorContextTermPayload, 'videoType'> & { videoType: string }) {
    isSavingCreatorContextTerm.value = true
    savingContextTermKey.value = `${payload.videoType}|${payload.termType}|${payload.term}`
    try {
      const term = await apiSaveContextTerm(payload as CreatorContextTermPayload)
      creatorContextTerms.value = [term, ...creatorContextTerms.value]
      return term
    } catch (error) {
      showError(error)
      return null
    } finally {
      isSavingCreatorContextTerm.value = false
      savingContextTermKey.value = ''
    }
  }

  async function disableContextTerm(term: CreatorContextTerm) {
    try {
      await disableCreatorContextTerm(term.termId)
      creatorContextTerms.value = creatorContextTerms.value.map((item) =>
        item.termId === term.termId ? { ...item, enabled: false } : item,
      )
    } catch (error) {
      showError(error)
    }
  }

  async function feedbackContextTerm(term: CreatorContextTerm, accepted: boolean) {
    try {
      await recordCreatorContextTermFeedback(term.termId, accepted)
    } catch (error) {
      showError(error)
    }
  }

  return {
    creatorPreferences,
    creatorContextTerms,
    isLoadingCreatorPreferences,
    isLoadingCreatorContextTerms,
    isSavingCreatorContextTerm,
    savingContextTermKey,
    loadCreatorPreferences,
    loadCreatorContextTerms,
    saveContextTerm,
    disableContextTerm,
    feedbackContextTerm,
  }
}
