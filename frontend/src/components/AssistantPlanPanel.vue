<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import {
  cancelAssistantPlan, confirmAssistantPlan, getAssistantPlans, getPlatformOperations, resumeAssistantPlan,
  submitAssistantPlanFiles, submitAssistantPlanInputs,
  type AssistantPlan, type AssistantPlanProgress, type PlatformOperation,
} from '@/api/assistant'
import { useMediaUpload } from '@/composables/creator/useMediaUpload'
import { ASSISTANT_BUSINESS_CHANGED } from '@/composables/useAssistantBusinessRefresh'
import { useAppStore } from '@/stores/appStore'

const props = defineProps<{ generating: boolean; stepCount: number }>()
const emit = defineEmits<{ active: [value: boolean] }>()
const router = useRouter()
const appStore = useAppStore()
const plans = ref<AssistantPlan[]>([])
const operations = ref<PlatformOperation[]>([])
const error = ref('')
const busy = ref('')
const inputs = reactive<Record<string, Record<string, string>>>({})
const selectedFiles = reactive<Record<string, File[]>>({})
const uploader = useMediaUpload()
const labels: Record<string, string> = {
  taskId: '任务', request: '具体内容', body: '具体内容', idea: '创作想法', taskName: '任务名称', videoType: '视频类型',
  titleDraft: '标题草稿', descriptionDraft: '简介草稿', manuscript: '文稿', subtitle: '字幕',
  userId: '工作台用户', versionId: '成片版本', versionName: '版本名称', sessionId: '沟通会话',
  optionId: '选中的发布方案', suggestionId: '发布方案', bvId: 'BV号', bv: 'BV号', bilibiliUid: 'B站UID',
  targetAudience: '目标受众', targetDurationSeconds: '目标时长（秒）', corePromise: '核心承诺',
  availableAssets: '已有素材', availableTools: '可用工具', guidance: '本次要求', extraRequirement: '补充要求',
  provider: '模型供应商', llmBaseUrl: '模型接口地址', llmModelName: '模型名称', embeddingModelName: '向量模型名称',
  llmApiKey: '模型API密钥', embeddingApiKey: '向量模型API密钥', videoCategory: '视频分类', productionMethod: '制作方式',
  frameIntervalSeconds: '抽帧间隔（秒）', resolution: '视频清晰度', modelPlan: '分析模型方案', includeAsr: '识别人声字幕',
  constraints: '制作限制', preferredTools: '偏好工具', materialType: '稿件类型', reviewFocus: '试映关注点',
  feedbackText: '反馈原文', commentText: '评论', danmakuText: '弹幕', maxItems: '最多处理数量',
  memoryId: '记忆', memoryKey: '记忆分类', sourceSessionId: '来源会话', configId: '模型配置',
  termId: '语境词条', term: '词条内容', termType: '词条类型', polarity: '词条倾向', sourceType: '来源类型',
  rids: '采集分区', maxPages: '最多采集页数', minIndex: '最低指数', onlyPopular: '仅采集热门',
  embeddingBaseUrl: '向量接口地址', enabled: '是否开启', value: '设置值', settingKey: '设置项',
  content: '内容', note: '备注', status: '状态', category: '分区', query: '检索内容', limit: '查询数量',
}
const active = computed(() => plans.value.some(p => ['PENDING', 'WAIT_INPUT', 'RUNNING', 'WAITING', 'CANCEL_REQUESTED', 'INTERRUPTED'].includes(p.status)))
const groups = computed(() => [...new Set(operations.value.map(o => o.group))])
let timer: ReturnType<typeof setTimeout> | undefined
let generation = 0
let disposed = false
let loaded = false
const completed = new Set<string>()

watch(active, value => emit('active', value), { immediate: true })
watch(() => [props.generating, props.stepCount], () => {
  clearTimeout(timer)
  timer = setTimeout(() => void refresh(), 450)
})
void getPlatformOperations().then(value => { if (!disposed) operations.value = value }).catch(() => {
  if (!disposed) error.value = '平台操作目录暂时无法读取，请刷新或更新服务后重试。'
})
void refresh()
onBeforeUnmount(() => {
  disposed = true; generation++; clearTimeout(timer); uploader.disposeUpload()
  Object.keys(inputs).forEach(key => delete inputs[key])
})

async function refresh() {
  const current = ++generation
  clearTimeout(timer)
  try {
    const result = await getAssistantPlans()
    if (current !== generation || disposed) return
    plans.value = result
    for (const plan of result) {
      for (let index = 0; index < plan.state.steps.length; index++) {
        const progress = plan.state.steps[index]!
        const step = plan.definition.steps[index]!
        const key = `${plan.planId}:${progress.id}`
        if (progress.status === 'SUCCEEDED' && !completed.has(key)) {
          completed.add(key)
          if (loaded && plan.operations[index]?.access === 'WRITE') {
            const taskId = String((progress.result as Record<string, unknown> | null)?.taskId || progress.arguments?.taskId || '')
            window.dispatchEvent(new CustomEvent(ASSISTANT_BUSINESS_CHANGED, { detail: { operation: step.operation, taskId } }))
          }
        }
      }
      inputs[plan.planId] ||= {}
      if (!['WAIT_INPUT', 'RUNNING'].includes(plan.status)) {
        delete inputs[plan.planId]; delete selectedFiles[plan.planId]
      }
    }
    loaded = true
    if (result.some(p => ['RUNNING', 'WAITING', 'CANCEL_REQUESTED'].includes(p.status))) timer = setTimeout(() => void refresh(), 2000)
  } catch (reason) { if (!disposed) error.value = message(reason) }
}

async function act(plan: AssistantPlan, action: 'confirm' | 'resume' | 'cancel') {
  if (busy.value) return
  busy.value = plan.planId; error.value = ''
  try {
    if (action === 'confirm') await confirmAssistantPlan(plan)
    else if (action === 'resume') await resumeAssistantPlan(plan.planId)
    else await cancelAssistantPlan(plan.planId)
  } catch (reason) { error.value = message(reason) }
  finally { busy.value = ''; await refresh() }
}

function waiting(plan: AssistantPlan) { return plan.state.steps.find(s => s.status === 'WAIT_INPUT') }
function operation(plan: AssistantPlan, stepId: string) { return plan.operations[plan.definition.steps.findIndex(s => s.id === stepId)] }
function valuesFor(plan: AssistantPlan, progress: AssistantPlanProgress) {
  const values: Record<string, unknown> = {}
  for (const field of progress.inputs) {
    const value = inputs[plan.planId]?.[field.path] ?? ''
    if (!value.trim()) throw new Error(`请填写${field.label}`)
    values[field.path] = ['object', 'array', 'boolean', 'number', 'integer'].includes(field.schema.type || '') ? JSON.parse(value) : value
  }
  return values
}

async function submit(plan: AssistantPlan) {
  if (busy.value) return
  const progress = waiting(plan)
  if (!progress) return
  busy.value = plan.planId; error.value = ''
  try {
    const values = valuesFor(plan, progress)
    const kind = operation(plan, progress.id)?.interaction
    if (kind === 'VIDEO') {
      const file = selectedFiles[plan.planId]?.[0]
      if (!file) throw new Error('请先选择本地MP4成片')
      const taskId = String(progress.arguments?.taskId || '')
      const versionName = String(progress.arguments?.versionName || '助手上传成片')
      await uploader.restoreStoredUpload(taskId)
      await uploader.startOrResume(taskId, file, versionName)
      const draft = uploader.completedDraft.value
      if (!draft) throw new Error(uploader.errorMessage.value || '上传尚未完成，请保留文件并继续上传')
      await submitAssistantPlanInputs(plan, { ...values, versionId: draft.versionId, uploadSessionId: uploader.currentUpload.value?.uploadSessionId })
    } else if (kind === 'FILES') {
      const files = selectedFiles[plan.planId] || []
      if (!files.length) throw new Error('请先选择要导入的文件')
      await submitAssistantPlanFiles(plan, files, values)
    } else await submitAssistantPlanInputs(plan, values)
  } catch (reason) { error.value = message(reason) }
  finally {
    // 密码仅保留在当前输入过程中，提交结束就清空，永不放入聊天和本地存储。
    for (const field of progress.inputs) if (field.secret && inputs[plan.planId]) inputs[plan.planId]![field.path] = ''
    busy.value = ''; await refresh()
  }
}

function selectFiles(plan: AssistantPlan, event: Event) { selectedFiles[plan.planId] = Array.from((event.target as HTMLInputElement).files || []) }
function status(value: string) {
  return ({ PENDING: '待确认', RUNNING: '执行中', WAIT_INPUT: '等你补充', WAITING: '等待后台完成', WAIT_JOB: '后台处理中',
    SUCCEEDED: '已完成', FAILED: '未完成', CANCEL_REQUESTED: '正在停止', CANCELLED: '已停止', INTERRUPTED: '执行中断' } as Record<string, string>)[value] || value
}
function display(value: unknown, plan: AssistantPlan): string {
  if (value === null || value === undefined) return '未指定'
  if (Array.isArray(value)) return value.map(v => display(v, plan)).join('、')
  if (typeof value === 'object') {
    const object = value as Record<string, unknown>
    if (object.$input) return `等待你提供：${object.$input}${object.secret ? '（专用密码框）' : ''}`
    if (object.$ref) {
      const [id, path] = String(object.$ref).split(':')
      const op = operation(plan, id || '')
      return `使用“${op?.label || '前一步'}”返回的${labels[(path || '').split('/').pop() || ''] || '结果'}`
    }
    return Object.entries(object).map(([key, val]) => `${labels[key] || key}：${typeof val === 'string' ? plan.state.targets?.[val] || val : display(val, plan)}`).join('；')
  }
  return typeof value === 'boolean' ? value ? '是' : '否' : String(value)
}
function open(plan: AssistantPlan) {
  const last = plan.definition.steps.at(-1)?.operation || ''
  if (last === 'tasks.deleteTask') { void router.push('/projects'); return }
  if (/^(settings|models|prompts)\./.test(last)) { appStore.settingsOpen = true; return }
  if (/^(references|collection|maintenance)\./.test(last)) { void router.push('/knowledge'); return }
  if (last.startsWith('memory.')) { void router.push('/memory'); return }
  if (last.startsWith('usage.')) { void router.push('/usage-logs'); return }
  const result = [...plan.state.steps].reverse().find(s => s.result && typeof s.result === 'object' && 'taskId' in (s.result as object))
  const taskId = String((result?.result as Record<string, unknown> | undefined)?.taskId || plan.taskId || '')
  if (!taskId) { void router.push('/creator'); return }
  const step = last.startsWith('review.') ? 'report' : last.startsWith('feedback.') ? 'feedback'
    : /^(media|processing|screening)/.test(last) ? 'preflight' : undefined
  void router.push({ path: '/creator', query: { taskId, step, reload: String(Date.now()) } })
}
function resultText(value: unknown): string {
  if (value === null || value === undefined) return '尚无业务结果'
  if (typeof value === 'string') return value
  if (Array.isArray(value)) return `共返回 ${value.length} 条结果。${value.slice(0, 8).map(resultText).join('\n')}`
  if (typeof value !== 'object') return String(value)
  const object = value as Record<string, unknown>
  const fields: Record<string, string> = {
    taskName: '任务名称', idea: '创作想法', videoType: '视频类型', status: '状态', provider: '模型供应商',
    contentSummary: '内容摘要', overallConclusion: '总体结论', feedbackSummary: '反馈摘要',
    understandingSummary: '创作理解', executiveSummary: '试映结论', planTitle: '蓝图名称', positioningSummary: '定位说明',
    originalFileName: '上传文件', versionName: '成片版本', progressPercent: '完成进度', costNotice: '费用说明',
    message: '执行说明', failureMessage: '未完成原因', llmModelName: '模型名称',
    importedCount: '导入数量', skippedCount: '跳过数量', failedCount: '未完成数量', content: '内容',
  }
  const lines = Object.entries(fields).filter(([key]) => object[key] !== null && object[key] !== undefined)
    .map(([key, label]) => `${label}：${String(object[key])}`)
  if (object.plan) lines.push(resultText(object.plan))
  if (object.completed === true) lines.push('原业务服务已完成此操作。')
  return lines.length ? lines.join('\n') : '原业务服务已返回结果，可打开业务页面查看完整内容。'
}
function message(reason: unknown) { return reason instanceof Error ? reason.message : '暂时无法读取执行结果，请刷新查看' }
</script>

<template>
  <div class="assistant-plans">
    <details class="assistant-capabilities">
      <summary>平台操作 · {{ operations.length }} 项能力 <span>查看能做什么</span></summary>
      <p>可以直接描述目标。我会查询实际状态，展示具体计划，确认后执行。</p>
      <div v-for="group in groups" :key="group" class="assistant-capability-group">
        <strong>{{ group }}</strong><p>{{ operations.filter(o => o.group === group).map(o => o.label).join('、') }}</p>
      </div>
    </details>
    <p v-if="error" class="plan-error" role="alert">{{ error }}</p>
    <article v-for="plan in plans.slice(0, 8)" :key="plan.planId" class="assistant-plan" :data-status="plan.status">
      <details :open="['PENDING', 'WAIT_INPUT', 'RUNNING', 'WAITING', 'INTERRUPTED'].includes(plan.status)">
        <summary><strong>{{ plan.title }}</strong><span>{{ status(plan.status) }}</span></summary>
        <p class="plan-message">{{ plan.state.message }}</p>
        <ol class="plan-steps">
          <li v-for="(step, index) in plan.definition.steps" :key="step.id">
            <div><strong>{{ plan.operations[index]?.label }}</strong><span>{{ status(plan.state.steps[index]?.status || 'PENDING') }}</span></div>
            <p>{{ display(step.arguments, plan) }}</p>
            <p v-if="step.note">{{ step.note }}</p>
            <p v-if="plan.operations[index]?.access === 'WRITE'" class="plan-impact">{{ plan.operations[index]?.impact }}</p>
            <p v-if="plan.state.steps[index]?.error" class="plan-error">{{ plan.state.steps[index]?.error }}</p>
            <small v-if="plan.state.steps[index]?.verification?.kind === 'READ_BACK'">已从原业务回查结果</small>
            <details v-if="plan.state.steps[index]?.result"><summary>查看实际返回结果</summary><pre>{{ resultText(plan.state.steps[index]?.result) }}</pre></details>
          </li>
        </ol>
        <p v-if="plan.status === 'PENDING'" class="plan-notice">确认后按以上动作连续执行，可能调用模型并产生费用。新增动作或修改已确定参数会重新确认。</p>
        <button v-if="plan.status === 'PENDING'" class="plan-primary" :disabled="!!busy || props.generating" @click="act(plan, 'confirm')">确认整份计划并执行</button>
        <form v-if="plan.status === 'WAIT_INPUT' && waiting(plan)" @submit.prevent="submit(plan)">
          <label v-for="field in waiting(plan)!.inputs" :key="field.path">
            {{ field.label }}<small v-if="field.secret">仅提交给配置服务，不进入聊天</small>
            <select v-if="field.schema.enum" v-model="inputs[plan.planId]![field.path]" required><option value="">请选择</option><option v-for="value in field.schema.enum" :key="value" :value="value">{{ value }}</option></select>
            <select v-else-if="field.schema.type === 'boolean'" v-model="inputs[plan.planId]![field.path]" required><option value="">请选择</option><option value="true">是</option><option value="false">否</option></select>
            <input v-else-if="field.secret" v-model="inputs[plan.planId]![field.path]" type="password" autocomplete="new-password" required />
            <textarea v-else v-model="inputs[plan.planId]![field.path]" :placeholder="['object', 'array'].includes(field.schema.type || '') ? '按展示的参数结构填写JSON' : '填写本次操作的具体内容'" required />
          </label>
          <label v-if="['FILES', 'VIDEO'].includes(operation(plan, waiting(plan)!.id)?.interaction || '')">
            {{ operation(plan, waiting(plan)!.id)?.interaction === 'VIDEO' ? '选择本地MP4成片' : '选择要导入的文件' }}
            <input type="file" :multiple="operation(plan, waiting(plan)!.id)?.parameters?.['x-multiple-files'] === true" :accept="operation(plan, waiting(plan)!.id)?.interaction === 'VIDEO' ? '.mp4,video/mp4' : undefined" @change="selectFiles(plan, $event)" />
          </label>
          <p v-if="busy === plan.planId && uploader.isUploading.value">成片上传 {{ uploader.progressPercent.value }}%</p>
          <button class="plan-primary" :disabled="!!busy || props.generating">{{ busy === plan.planId ? '正在提交…' : '确认本次输入并继续' }}</button>
        </form>
        <div class="plan-buttons">
          <button v-if="plan.status === 'INTERRUPTED'" :disabled="!!busy" @click="act(plan, 'resume')">检查并继续</button>
          <button v-if="!['SUCCEEDED', 'FAILED', 'CANCELLED'].includes(plan.status)" :disabled="!!busy" @click="act(plan, 'cancel')">停止后续步骤</button>
          <button v-if="plan.state.steps.some(s => s.status === 'SUCCEEDED')" @click="open(plan)">打开业务页面</button>
          <button @click="refresh">刷新状态</button>
        </div>
      </details>
    </article>
  </div>
</template>

<style scoped>
.assistant-plans { margin-top: 10px; font-size: 12px; line-height: 1.6; }
summary { cursor: pointer; display: flex; align-items: center; justify-content: space-between; gap: 8px; }
summary span { opacity: .7; font-size: 11px; flex-shrink: 0; }
.assistant-capabilities { border: 1px solid var(--border); border-radius: 8px; padding: 8px 10px; }
.assistant-capability-group { margin-top: 8px; }.assistant-capability-group p { margin: 2px 0; opacity: .75; }
.assistant-plan { margin-top: 10px; padding: 12px; background: var(--surface); border: 1px solid var(--border); border-radius: 10px; }
.plan-message { margin: 8px 0; }.plan-steps { padding-left: 20px; margin: 8px 0; }.plan-steps li { margin: 10px 0; }
.plan-steps li>div { display: flex; justify-content: space-between; gap: 8px; }.plan-steps li>div>span, small { font-size: 11px; opacity: .72; }
.plan-steps p { margin: 4px 0; overflow-wrap: anywhere; }.plan-notice { padding: 8px; background: var(--surface-sub); border-radius: 6px; }
button { border: 1px solid var(--border); border-radius: 7px; background: var(--surface); color: inherit; padding: 6px 10px; font: inherit; cursor: pointer; }
button:disabled { opacity: .5; cursor: default; }.plan-primary { background: var(--accent); border-color: var(--accent); color: white; }
.plan-buttons { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 10px; }.plan-error { color: #b42b3e; }
form { display: grid; gap: 10px; margin-top: 10px; }label { display: grid; gap: 4px; }input, textarea, select { box-sizing: border-box; width: 100%; padding: 8px; border: 1px solid var(--border); border-radius: 6px; background: var(--surface); color: inherit; font: inherit; }
textarea { min-height: 65px; resize: vertical; }pre { max-height: 220px; overflow: auto; white-space: pre-wrap; overflow-wrap: anywhere; font-size: 11px; }
</style>
