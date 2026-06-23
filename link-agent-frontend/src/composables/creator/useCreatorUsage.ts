import { computed, ref } from 'vue'
import type { Ref } from 'vue' // eslint-disable-line @typescript-eslint/no-unused-vars
import { getTaskLlmUsageSummary, listTaskLlmApiCalls } from '@/api/creator'
import type { LlmApiCallPage, LlmApiModelCategory, LlmApiUsageSummary } from '@/types/creator'

export function useCreatorUsage(
  getSelectedTaskId: () => string,
  errorRef: Ref<string>,
) {
  // ── 状态 ──
  const usageSummary = ref<LlmApiUsageSummary | null>(null)
  const usageCallPage = ref<LlmApiCallPage | null>(null)
  const usageCategoryFilter = ref<'ALL' | LlmApiModelCategory>('ALL')
  const usageCurrentPage = ref(1)
  const isLoadingUsageStats = ref(false)

  // ── 计算属性 ──
  const usageCategorySummaries = computed(() => {
    const existing = new Map<LlmApiModelCategory, LlmApiUsageSummary['categories'][number]>()
    for (const item of usageSummary.value?.categories ?? []) {
      existing.set(item.modelCategory, item)
    }
    return (['TEXT', 'EMBEDDING', 'RERANK'] as LlmApiModelCategory[]).map(
      (category) =>
        existing.get(category) ?? {
          modelCategory: category,
          callCount: 0,
          successCount: 0,
          failedCount: 0,
          skippedCount: 0,
          totalTokens: null,
          promptTokens: null,
          completionTokens: null,
          totalElapsedMs: null,
          averageElapsedMs: null,
        },
    )
  })

  const usageTotalPages = computed(() => {
    if (!usageCallPage.value || usageCallPage.value.pageSize <= 0) return 1
    return Math.max(1, Math.ceil(usageCallPage.value.total / usageCallPage.value.pageSize))
  })

  // ── 方法 ──
  function showError(error: unknown) {
    errorRef.value = error instanceof Error ? error.message : String(error)
  }

  async function refreshUsageStats(page = usageCurrentPage.value, reportError = true) {
    if (!getSelectedTaskId()) {
      usageSummary.value = null
      usageCallPage.value = null
      return
    }
    isLoadingUsageStats.value = true
    try {
      const modelCategory =
        usageCategoryFilter.value === 'ALL' ? undefined : usageCategoryFilter.value
      const [summary, callPage] = await Promise.all([
        getTaskLlmUsageSummary(getSelectedTaskId()),
        listTaskLlmApiCalls(getSelectedTaskId(), page, 20, modelCategory),
      ])
      usageSummary.value = summary
      usageCallPage.value = callPage
      usageCurrentPage.value = callPage.page
    } catch (error) {
      if (reportError) showError(error)
    } finally {
      isLoadingUsageStats.value = false
    }
  }

  async function changeUsageCategoryFilter(category: 'ALL' | LlmApiModelCategory) {
    usageCategoryFilter.value = category
    usageCurrentPage.value = 1
    await refreshUsageStats(1)
  }

  async function changeUsagePage(delta: number) {
    const nextPage = Math.min(
      Math.max(1, usageCurrentPage.value + delta),
      usageTotalPages.value,
    )
    if (nextPage === usageCurrentPage.value) return
    await refreshUsageStats(nextPage)
  }

  return {
    usageSummary,
    usageCallPage,
    usageCategoryFilter,
    usageCurrentPage,
    isLoadingUsageStats,
    usageCategorySummaries,
    usageTotalPages,
    refreshUsageStats,
    changeUsageCategoryFilter,
    changeUsagePage,
  }
}
