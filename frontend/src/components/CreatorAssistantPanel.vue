<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import PlanTracePanel from '@/components/PlanTracePanel.vue'
import AssistantPlanPanel from '@/components/AssistantPlanPanel.vue'
import { useAssistantBusinessRefresh } from '@/composables/useAssistantBusinessRefresh'
import {
  confirmAssistantAction, getAssistantActions, getAssistantContext, getBusinessAgentTraces,
  parseBusinessTrace, prepareAssistantAction,
  type AssistantAction, type AssistantActionType, type AssistantTaskContext, type BusinessAgentTrace,
} from '@/api/assistant'

const props = defineProps<{ taskId?: string; page: string; generating: boolean; stepCount: number }>()
const emit = defineEmits<{ ask: [message: string] }>()
const router = useRouter()
const context = ref<AssistantTaskContext | null>(null)
const actions = ref<AssistantAction[]>([])
const traces = ref<BusinessAgentTrace[]>([])
const error = ref('')
const loading = ref(false)
const busy = ref(false)
const planActive = ref(false)
useAssistantBusinessRefresh([''], () => refresh())
let generation = 0
let refreshTimer: ReturnType<typeof setTimeout> | undefined
let pollingTimer: ReturnType<typeof setTimeout> | undefined
const pageLabel = computed(() => ({
  '/creator': 'AI 创作台', '/projects': '创作任务', '/knowledge': '参考案例库',
  '/video-analysis': '视频与观众反馈', '/memory': '长期记忆', '/usage-logs': '模型使用记录',
}[props.page] || 'LinkAgent 工作台'))
const questions = computed(() => props.taskId
  ? ['这期任务目前卡在哪一步？', '下一步应该怎么做？']
  : ['这个页面怎么用？', '我已有成片，应该从哪里开始？'])
const hasPendingAction = computed(() => planActive.value || actions.value.some(item => item.status === 'RUNNING'
  || (item.status === 'PENDING' && !expired(item))))

watch(() => [props.taskId, props.page], () => {
  generation += 1
  context.value = null
  actions.value = []
  traces.value = []
  error.value = ''
  void refresh()
}, { immediate: true })
watch(() => [props.generating, props.stepCount], () => {
  // 工具可能刚创建确认卡，延迟合并刷新，避免每个 token 都查业务数据。
  clearTimeout(refreshTimer)
  refreshTimer = setTimeout(() => void refresh(), 400)
})
onBeforeUnmount(() => { generation += 1; clearTimeout(refreshTimer); clearTimeout(pollingTimer) })

async function refresh() {
  clearTimeout(pollingTimer)
  const version = ++generation
  const taskId = props.taskId
  loading.value = true
  try {
    const results = await Promise.all([
      getAssistantContext({ page: props.page, taskId }),
      taskId ? getAssistantActions(taskId) : Promise.resolve([]),
      taskId ? getBusinessAgentTraces(taskId) : Promise.resolve([]),
    ])
    if (version !== generation) return
    ;[context.value, actions.value, traces.value] = results
    error.value = ''
    if (actions.value.some(item => item.status === 'RUNNING') || traces.value.some(item => item.status === 'RUNNING')) {
      pollingTimer = setTimeout(() => void refresh(), 2000)
    }
  } catch (reason) {
    if (version === generation) error.value = reason instanceof Error ? reason.message : '读取工作台上下文失败'
  } finally {
    if (version === generation) loading.value = false
  }
}

async function prepare(type: AssistantActionType) {
  if (!props.taskId || busy.value) return
  const taskId = props.taskId
  busy.value = true
  error.value = ''
  try {
    const proposal = await prepareAssistantAction(taskId, type)
    if (taskId === props.taskId) actions.value = [proposal, ...actions.value.filter(item => item.proposalId !== proposal.proposalId)].slice(0, 5)
  } catch (reason) {
    if (taskId === props.taskId) error.value = reason instanceof Error ? reason.message : '准备操作失败'
  } finally { busy.value = false }
}

async function confirm(action: AssistantAction) {
  if (busy.value || action.taskId !== props.taskId || action.status !== 'PENDING') return
  busy.value = true
  error.value = ''
  action.status = 'RUNNING'
  pollingTimer = setTimeout(() => void refresh(), 1000)
  try {
    const result = await confirmAssistantAction(action.proposalId, action.taskId)
    if (action.taskId === props.taskId) Object.assign(action, result)
  } catch (reason) {
    // 请求超时不代表业务失败，读取持久化状态，不自动重发收费操作。
    if (action.taskId === props.taskId) error.value = reason instanceof Error ? reason.message : '执行状态暂时无法确认，请刷新查看'
  } finally {
    busy.value = false
    if (action.taskId === props.taskId) await refresh()
  }
}

function label(type: AssistantActionType) { return type === 'ANALYZE_FEEDBACK' ? '分析观众反馈' : '生成总体复盘' }
function expired(action: AssistantAction) { return Date.parse(action.expiresAt) <= Date.now() }
function statusLabel(status: string) {
  return ({ PENDING: '待确认', RUNNING: '正在执行', SUCCEEDED: '已完成', FAILED: '未完成' } as Record<string, string>)[status] || status
}
function openResult(action: AssistantAction) {
  void router.push({ path: '/creator', query: { taskId: action.taskId, step: action.actionType === 'ANALYZE_FEEDBACK' ? 'feedback' : 'report' } })
}
</script>

<template>
  <section class="creator-assistant-panel" :class="{ 'has-pending-action': hasPendingAction }" aria-label="创作助手的工作台上下文">
    <div class="assistant-context-heading">
      <div><small>{{ pageLabel }}</small><strong>{{ context?.taskName || 'LinkAgent 助手' }}</strong></div>
      <button type="button" :disabled="loading" @click="refresh">{{ loading ? '读取中…' : '刷新状态' }}</button>
    </div>
    <p v-if="context?.blockingReason && props.taskId" class="assistant-blocking">{{ context.blockingReason }}</p>
    <p v-else-if="!props.taskId" class="assistant-hint">可以直接描述目标，例如“帮我新建一期视频任务”，确认具体计划后执行。</p>
    <AssistantPlanPanel :generating="props.generating" :step-count="props.stepCount" @active="planActive = $event" />
    <div v-if="!hasPendingAction" class="assistant-shortcuts">
      <button v-for="question in questions" :key="question" type="button" @click="emit('ask', question)">{{ question }}</button>
      <button v-for="type in context?.availableActions || []" :key="type" type="button" :disabled="busy || props.generating" @click="prepare(type)">{{ label(type) }}</button>
    </div>
    <p v-if="error" class="assistant-error" role="alert">{{ error }}</p>
    <div v-for="action in actions" :key="action.proposalId" class="assistant-action" :data-status="action.status">
      <div class="assistant-action-title"><strong>{{ label(action.actionType) }}</strong><span>{{ statusLabel(action.status) }}</span></div>
      <small>任务：{{ action.taskName }}</small>
      <p>{{ action.description }}</p>
      <p v-if="action.guidance">本次要求：{{ action.guidance }}</p>
      <p v-if="action.resultMessage">{{ action.resultMessage }}</p>
      <template v-if="action.status === 'PENDING'">
        <small v-if="expired(action)">提议已过期，请重新准备操作。</small>
        <button v-else type="button" class="assistant-confirm" :disabled="busy || props.generating" @click="confirm(action)">确认并执行</button>
      </template>
      <button v-if="action.status === 'SUCCEEDED'" type="button" @click="openResult(action)">查看业务结果</button>
      <small v-if="action.status === 'RUNNING'">执行结果会保存到原业务页面，可刷新查看状态。</small>
    </div>
    <details v-for="trace in traces" :key="trace.runId" class="assistant-business-trace">
      <summary>{{ trace.scene }} · {{ statusLabel(trace.status) }}</summary>
      <p class="assistant-hint">这里记录实际取证与分工；最终报告是否保存，以业务页面结果为准。</p>
      <p v-if="trace.errorMessage" class="assistant-error">{{ trace.errorMessage }}</p>
      <PlanTracePanel :plan-trace="parseBusinessTrace(trace)?.planTrace" :worker-traces="parseBusinessTrace(trace)?.workerTraces" />
    </details>
  </section>
</template>

<style scoped>
.creator-assistant-panel { padding: 14px 18px; border-bottom: 1px solid var(--border); background: var(--surface-sub); max-height: 42%; overflow: auto; flex: 0 1 auto; }
.assistant-context-heading, .assistant-action-title { display: flex; justify-content: space-between; align-items: center; gap: 12px; }
.assistant-context-heading strong, .assistant-context-heading small { display: block; }
.assistant-context-heading small, .assistant-hint, .assistant-action small { opacity: .72; font-size: 12px; line-height: 1.6; }
.assistant-blocking, .assistant-action p { margin: 8px 0; font-size: 12px; line-height: 1.65; }
.assistant-shortcuts { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 10px; }
button { border: 1px solid var(--border); border-radius: 7px; background: var(--surface); color: inherit; padding: 6px 10px; font: inherit; font-size: 12px; cursor: pointer; }
button:disabled { opacity: .5; cursor: default; }
.assistant-action { margin-top: 12px; padding: 12px; border: 1px solid var(--border); border-radius: 10px; background: var(--surface); }
.assistant-action-title { font-size: 13px; margin-bottom: 4px; }
.assistant-action-title span { font-size: 11px; opacity: .7; }
.assistant-confirm { margin-top: 8px; background: var(--accent); border-color: var(--accent); color: white; }
.assistant-error { color: #b42b3e; font-size: 12px; line-height: 1.65; }
.assistant-business-trace { padding-top: 12px; font-size: 12px; }
.assistant-business-trace summary { cursor: pointer; }
@media (max-width: 600px) { .creator-assistant-panel { padding: 10px 12px; max-height: 38%; } }
.creator-assistant-panel.has-pending-action { max-height: 65%; flex-shrink: 0; }
</style>
