<script setup lang="ts">
/**
 * 竞品对比分析弹窗（P1-1：参考案例体系融入竞品分析）。
 * <p>
 * 用户在知识库页面点击竞品卡片上的「对比我的创作」按钮后弹出此弹窗，
 * 选择要对比的创作任务 → 可选填写分析重点 → 触发 AI 分析 → 展示结构化报告。
 * <p>
 * 为什么独立为组件而非内联在 KnowledgeWorkspace？
 * 弹窗逻辑包含任务列表加载、分析触发、报告展示三块独立状态，
 * 内联会使 KnowledgeWorkspace 的 script 和 template 过于臃肿。
 */
import { computed, ref, watch } from 'vue'
import { analyzeCompetitorByReference, listCreatorTasks } from '@/api/creator'
import type { ReferenceVideo } from '@/types/knowledge'
import type { CreatorCompetitorReport, CreatorTaskSummary } from '@/types/creator'

// ── Props & Emits ──

const props = defineProps<{
  /** 选中的参考案例视频；为 null 时弹窗隐藏 */
  target: ReferenceVideo | null
}>()

const emit = defineEmits<{
  /** 关闭弹窗 */
  close: []
}>()

// ── 任务列表 ──

const tasks = ref<CreatorTaskSummary[]>([])
const tasksLoading = ref(false)
const tasksError = ref('')
const selectedTaskId = ref('')
const eligibleStatuses = new Set(['FEEDBACK_ANALYZED', 'COMPETITOR_ANALYZED', 'ANALYZED'])

/** 加载创作任务列表，供用户选择要对比哪个任务 */
async function loadTasks() {
  tasksLoading.value = true
  tasksError.value = ''
  try {
    tasks.value = (await listCreatorTasks(50))
      .filter((task) => eligibleStatuses.has(task.status))
  } catch (err) {
    tasks.value = []
    tasksError.value = err instanceof Error ? err.message : String(err)
  } finally {
    tasksLoading.value = false
  }
}

/** 已选择的任务名称，用于展示确认信息 */
const selectedTaskName = computed(() => {
  const task = tasks.value.find((t) => t.taskId === selectedTaskId.value)
  return task?.taskName ?? ''
})

// ── 分析参数 ──

const customGuidance = ref('')
const analysisFocus = ref('')
const extraRequirement = ref('')

// ── 分析状态 ──

const analyzing = ref(false)
const analyzeError = ref('')
const report = ref<CreatorCompetitorReport | null>(null)

/** 是否已经触发过分析（用于控制结果区域的显示） */
const hasAnalyzed = ref(false)

/** 触发竞品分析 */
async function startAnalysis() {
  if (!props.target || !selectedTaskId.value) return
  const selectedTask = tasks.value.find((task) => task.taskId === selectedTaskId.value)
  if (!selectedTask || !eligibleStatuses.has(selectedTask.status)) return
  analyzing.value = true
  analyzeError.value = ''
  report.value = null
  try {
    report.value = await analyzeCompetitorByReference(selectedTaskId.value, {
      referenceVideoId: props.target.videoId,
      customGuidance: customGuidance.value.trim() || undefined,
      analysisFocus: analysisFocus.value.trim() || undefined,
      extraRequirement: extraRequirement.value.trim() || undefined,
    })
    hasAnalyzed.value = true
  } catch (err) {
    analyzeError.value = err instanceof Error ? err.message : String(err)
  } finally {
    analyzing.value = false
  }
}

/** 是否可以开始分析：必须选择了任务且有目标视频 */
const canAnalyze = computed(() =>
  Boolean(
    props.target &&
      tasks.value.some((task) => task.taskId === selectedTaskId.value) &&
      !analyzing.value,
  ),
)

watch(
  () => props.target?.videoId ?? '',
  (videoId) => {
    resetAnalysisState()
    if (videoId) void loadTasks()
  },
  { immediate: true },
)

// ── 关闭时重置内部状态（下次打开时是全新的一次分析） ──

function handleClose() {
  resetAnalysisState()
  emit('close')
}

function resetAnalysisState() {
  selectedTaskId.value = ''
  customGuidance.value = ''
  analysisFocus.value = ''
  extraRequirement.value = ''
  analyzeError.value = ''
  report.value = null
  hasAnalyzed.value = false
}

// ── 格式化工具 ──

/** 格式化 JSON 字符串为可读文本；用于报告中的嵌套 JSON 字段 */
function formatJsonField(value: string | null): string {
  if (!value) return '（暂无）'
  try {
    const parsed = JSON.parse(value)
    if (Array.isArray(parsed)) {
      return parsed.map((item, i) => `${i + 1}. ${typeof item === 'string' ? item : JSON.stringify(item)}`).join('\n')
    }
    return JSON.stringify(parsed, null, 2)
  } catch {
    return value
  }
}
</script>

<template>
  <!-- 弹窗隐藏时完全不渲染，避免空状态闪烁 -->
  <Teleport to="body">
    <Transition name="creator-modal">
      <div v-if="target" class="creator-modal-backdrop" role="presentation" @click.self="handleClose">
        <section class="creator-prompt-modal competitor-analysis-modal" role="dialog" aria-modal="true" aria-label="竞品对比分析">
          <!-- 头部 -->
          <header class="creator-result-modal-head">
            <h3>竞品对比分析</h3>
            <button type="button" class="creator-ghost-button" @click="handleClose">关闭</button>
          </header>

          <!-- 竞品信息摘要 -->
          <div class="competitor-analysis-target">
            <strong>竞品：</strong>
            <span>{{ target.title }}</span>
            <small v-if="target.bvId">{{ target.bvId }}</small>
          </div>

          <!-- 第一步：选择创作任务 -->
          <fieldset class="competitor-analysis-fieldset" :disabled="analyzing">
            <label class="competitor-analysis-label">
              <span>选择要对比的创作任务</span>
              <select v-model="selectedTaskId" class="competitor-analysis-select">
                <option value="" disabled>— 请选择任务 —</option>
                <option v-for="task in tasks" :key="task.taskId" :value="task.taskId">
                  {{ task.taskName }}
                </option>
              </select>
            </label>
            <p v-if="tasksLoading" class="creator-muted">加载任务列表…</p>
            <p v-else-if="tasksError" class="creator-muted error-text">加载失败：{{ tasksError }}</p>
            <p v-else-if="tasks.length === 0" class="creator-muted">
              暂无已完成反馈分析的任务。
            </p>
            <p v-else-if="selectedTaskName" class="creator-muted">
              将对「{{ selectedTaskName }}」进行竞品对比分析
            </p>
          </fieldset>

          <!-- 第二步：可选分析参数 -->
          <fieldset class="competitor-analysis-fieldset" :disabled="analyzing">
            <label class="competitor-analysis-label">
              <span>分析重点（可选）</span>
              <input
                v-model="analysisFocus"
                type="text"
                class="competitor-analysis-input"
                placeholder="如：标题策略对比、内容结构分析…"
                maxlength="500"
              />
            </label>
            <label class="competitor-analysis-label">
              <span>自定义分析指导（可选）</span>
              <input
                v-model="customGuidance"
                type="text"
                class="competitor-analysis-input"
                placeholder="如：请重点分析观众互动策略的差异…"
                maxlength="2000"
              />
            </label>
            <label class="competitor-analysis-label">
              <span>额外要求（可选）</span>
              <input
                v-model="extraRequirement"
                type="text"
                class="competitor-analysis-input"
                placeholder="如：请用表格形式输出对比结果…"
                maxlength="500"
              />
            </label>
          </fieldset>

          <!-- 操作按钮 -->
          <div class="competitor-analysis-actions">
            <button
              type="button"
              class="creator-primary-button"
              :disabled="!canAnalyze"
              @click="startAnalysis"
            >
              {{ analyzing ? '分析中…' : '开始分析' }}
            </button>
          </div>

          <!-- 错误提示 -->
          <div v-if="analyzeError" class="creator-alert error-alert">
            <strong>分析失败</strong>
            <span>{{ analyzeError }}</span>
          </div>

          <!-- 第三步：分析结果（分析完成后展示） -->
          <template v-if="hasAnalyzed && report">
            <hr class="competitor-analysis-divider" />
            <h4 class="competitor-analysis-result-title">分析结果</h4>

            <!-- 竞品总结 -->
            <section v-if="report.competitorSummary" class="competitor-analysis-section">
              <h5>竞品总结</h5>
              <p>{{ report.competitorSummary }}</p>
            </section>

            <!-- 竞品优势 -->
            <section v-if="report.competitorAdvantages" class="competitor-analysis-section">
              <h5>竞品优势</h5>
              <pre class="competitor-analysis-pre">{{ formatJsonField(report.competitorAdvantages) }}</pre>
            </section>

            <!-- 自身优势 -->
            <section v-if="report.ownAdvantages" class="competitor-analysis-section">
              <h5>自身优势</h5>
              <pre class="competitor-analysis-pre">{{ formatJsonField(report.ownAdvantages) }}</pre>
            </section>

            <!-- 自身短板 -->
            <section v-if="report.ownDisadvantages" class="competitor-analysis-section">
              <h5>自身短板</h5>
              <pre class="competitor-analysis-pre">{{ formatJsonField(report.ownDisadvantages) }}</pre>
            </section>

            <!-- 差距分析 -->
            <section v-if="report.gapAnalysis" class="competitor-analysis-section">
              <h5>差距分析</h5>
              <pre class="competitor-analysis-pre">{{ formatJsonField(report.gapAnalysis) }}</pre>
            </section>

            <!-- 改进建议 -->
            <section v-if="report.improvementSuggestions" class="competitor-analysis-section">
              <h5>改进建议</h5>
              <pre class="competitor-analysis-pre">{{ formatJsonField(report.improvementSuggestions) }}</pre>
            </section>

            <!-- 差异化策略 -->
            <section v-if="report.differentiationStrategy" class="competitor-analysis-section">
              <h5>差异化策略</h5>
              <p>{{ report.differentiationStrategy }}</p>
            </section>

            <!-- 原始输出兜底：解析失败时展示 -->
            <section v-if="report.parseStatus === 'RAW_ONLY'" class="competitor-analysis-section">
              <h5>原始分析输出</h5>
              <pre class="competitor-analysis-pre">{{ report.rawOutput }}</pre>
            </section>
          </template>

          <!-- 分析中占位 -->
          <div v-else-if="analyzing" class="competitor-analysis-loading">
            <p class="creator-muted">AI 正在分析竞品差异，请稍候…</p>
          </div>
        </section>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
/**
 * 竞品分析弹窗专属样式。
 * 弹窗框架复用全局 .creator-modal-backdrop / .creator-prompt-modal，
 * 这里只补本组件独有布局（字段集、结果段落、预格式化文本等）。
 */

.competitor-analysis-modal {
  max-width: 860px;
  max-height: 90vh;
  overflow-y: auto;
}

.competitor-analysis-target {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: var(--s2);
  padding: var(--s3);
  margin-bottom: var(--s3);
  background: var(--surface-dim, #f8f9fa);
  border-radius: var(--r-sm);
}

.competitor-analysis-target strong {
  color: var(--text);
  font-size: 14px;
}

.competitor-analysis-target span {
  font-weight: var(--fw-semibold);
}

.competitor-analysis-target small {
  color: var(--text-secondary, #666);
}

.competitor-analysis-fieldset {
  border: none;
  padding: 0;
  margin: 0 0 var(--s3) 0;
  display: grid;
  gap: var(--s3);
}

.competitor-analysis-label {
  display: grid;
  align-content: start;
  gap: var(--s2);
}

.competitor-analysis-label > span {
  color: var(--text);
  font-size: 13px;
  font-weight: var(--fw-semibold);
}

.competitor-analysis-select,
.competitor-analysis-input {
  width: 100%;
  min-height: 38px;
  padding: 0 var(--s3);
  color: var(--ink);
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--r-sm);
  font-size: 14px;
}

.competitor-analysis-select:focus,
.competitor-analysis-input:focus {
  outline: none;
  border-color: var(--accent, #1a73e8);
}

.competitor-analysis-actions {
  display: flex;
  gap: var(--s2);
  justify-content: flex-end;
  margin-bottom: var(--s3);
}

.competitor-analysis-divider {
  border: none;
  border-top: 1px solid var(--border);
  margin: var(--s3) 0;
}

.competitor-analysis-result-title {
  font-size: 16px;
  font-weight: var(--fw-semibold);
  margin: 0 0 var(--s3) 0;
}

.competitor-analysis-section {
  margin-bottom: var(--s3);
}

.competitor-analysis-section h5 {
  font-size: 14px;
  font-weight: var(--fw-semibold);
  color: var(--text);
  margin: 0 0 var(--s2) 0;
  padding-bottom: var(--s1);
  border-bottom: 1px solid var(--border);
}

.competitor-analysis-section p {
  margin: 0;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-word;
}

.competitor-analysis-pre {
  margin: 0;
  padding: var(--s3);
  background: var(--surface-dim, #f8f9fa);
  border-radius: var(--r-sm);
  font-size: 13px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
  overflow-x: auto;
}

.competitor-analysis-loading {
  padding: var(--s4);
  text-align: center;
}

.error-text {
  color: var(--danger, #d93025);
}
</style>
