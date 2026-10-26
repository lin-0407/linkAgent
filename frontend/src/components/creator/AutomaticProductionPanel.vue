<script setup lang="ts">
import { computed, ref } from 'vue'
import { useCreatorWorkspaceShell } from '@/composables/creator/useCreatorWorkspaceContext'
import { useAutomaticProduction } from '@/composables/creator/useAutomaticProduction'
import CreationSceneEditor from './CreationSceneEditor.vue'
import CreationVersionCompare from './CreationVersionCompare.vue'
import CreationProviderSettings from './CreationProviderSettings.vue'
import CreatorStageState from './CreatorStageState.vue'
import type { CreationAsset, Scene, AudioBed } from '@/types/creationProduction'
import { creationKey, assetDeletionImpact, deleteReadyAsset } from '@/api/creationProduction'
import { errorText } from '@/utils/error'

const emit = defineEmits<{ changed: [] }>()
const { selectedTaskId, selectedTask, suggestion, currentDraftVideo, refreshCurrentDraftVideo } =
  useCreatorWorkspaceShell()
const p = useAutomaticProduction(
  selectedTaskId,
  () => {
    emit('changed')
    void refreshCurrentDraftVideo(selectedTaskId.value)
  },
  () => ({
    topic: selectedTask.value?.taskName?.slice(0, 200) ?? '',
    coreMessage: suggestion.value?.contentSummary?.slice(0, 500) ?? '',
    audience: suggestion.value?.audienceProfile?.slice(0, 200) ?? '',
  }),
)
const {
  form,
  available,
  loading,
  busy,
  error,
  notice,
  runtime,
  brief,
  board,
  boards,
  scenes,
  dirty,
  budget,
  locked,
  briefDirty,
  readyAssets,
  preparation,
  preparationQuote,
  preparationLimit,
  quote,
  executionLimit,
  executions,
  preview,
  timing,
  instruction,
  proposal,
} = p
const library = p.library
const uploadKind = ref<CreationAsset['kind']>('IMAGE'),
  fileInput = ref<HTMLInputElement | null>(null)
const showBrief = ref(false)
const showSceneEditor = ref(false)
const showExecutionHistory = ref(false)
const productionView = ref<'canvas' | 'monitor'>('canvas')
function openBriefEditor() {
  showBrief.value = true
  productionView.value = 'canvas'
}
const briefComplete = computed(() =>
  Boolean(form.topic.trim() && form.coreMessage.trim() && form.audience.trim()),
)
const visibleExecutions = computed(() =>
  showExecutionHistory.value
    ? executions.value
    : executions.value.filter(
        (execution, index) => index === 0 || !['COMPLETED', 'CANCELLED'].includes(execution.status),
      ),
)
const totalDuration = computed(() =>
  Math.round(
    (scenes.value.reduce((sum, s) => sum + Math.round((s.durationMs * 30) / 1000), 0) * 1000) / 30,
  ),
)
const money = (amount: unknown) =>
  Number(amount ?? 0)
    .toFixed(6)
    .replace(/0+$/, '')
    .replace(/\.$/, '') || '0'
const labels: Record<string, string> = {
  ACTIVE: '制作中',
  WAITING_USER: '需要处理',
  CANCEL_REQUESTED: '正在停止',
  CANCELLED: '已结束',
  COMPLETED: '已完成',
  QUEUED: '等待执行',
  VOICE: '准备配音与字幕',
  VIDEO: '准备 AI 镜头',
  SAMPLE: '渲染样片',
  FULL: '整片制作与试映',
  RUNNING: '执行中',
  WAITING_DEPENDENCY: '等待媒体处理与试映',
  CREATING: '准备上传会话',
  CREATED: '等待上传',
  UPLOADING: '正在上传',
  UPLOADED: '正在校验',
  VERIFYING: '正在校验',
  PREPARING: '正在准备',
  READY: '可使用',
  FAILED: '失败',
  CANCELLING: '正在取消',
}
const label = (value: string) => labels[value] ?? '处理中'
const kindLabel = (kind: string) =>
  kind === 'SAMPLE' ? '样片' : kind === 'REVISION' ? '修改版' : '整片'
const fieldLabels: Record<string, string> = {
  narration: '旁白文稿',
  visualAssetId: '画面素材',
  layout: '画面形式',
  sourceInMs: '截取起点',
  sourceOutMs: '截取终点',
  durationMs: '场景时长',
  audioMode: '声音来源',
  narrationAssetId: '录音素材',
  fit: '画面适配',
  position: '场景顺序',
  subtitleFontSize: '字幕字号',
  targetDurationMs: '目标时长',
  backgroundMusic: '背景音乐',
}
function differenceText(value: unknown, field: string) {
  if (value == null) return '未设置'
  if (field === 'position') return `第 ${Number(value) + 1} 个`
  if (field.endsWith('Ms')) return `${(Number(value) / 1000).toFixed(3)} 秒`
  if (field.endsWith('AssetId'))
    return readyAssets.value.find((a) => a.assetId === value)?.fileName ?? '已保存素材'
  if (typeof value === 'object') return '音轨设置'
  const names: Record<string, string> = {
    TTS: 'AI 配音',
    NARRATION: '自己的录音',
    ORIGINAL: '片段原声',
    NO_DIALOGUE: '无对白',
    CONTAIN: '完整显示',
    COVER: '居中裁切',
    TITLE_CARD: '文字卡',
    IMAGE_CARD: '图片',
    SPLIT_CARD: '图文并排',
    COMPARE_CARD: '双栏对比',
    VIDEO_CLIP: '视频片段',
    AI_VIDEO: 'AI 视频',
  }
  return names[String(value)] ?? String(value)
}
async function deleteAsset(asset: CreationAsset) {
  const task = selectedTaskId.value
  try {
    const impact = await assetDeletionImpact(task, asset.assetId)
    if (task !== selectedTaskId.value) return
    if (!impact.allowed) {
      notice.value = `${impact.message} 当前有 ${impact.references} 处引用。`
      return
    }
    if (!window.confirm(`${impact.message} 确定删除“${asset.fileName}”吗？`)) return
    await deleteReadyAsset(task, asset.assetId)
    if (task !== selectedTaskId.value) return
    form.assetIds = form.assetIds.filter((id) => id !== asset.assetId)
    if (form.backgroundMusic?.assetId === asset.assetId) form.backgroundMusic = null
    if (form.ambience?.assetId === asset.assetId) form.ambience = null
    await p.refresh(false, true)
  } catch (reason) {
    if (task === selectedTaskId.value) error.value = errorText(reason, '素材删除失败')
  }
}
const assetKind: Record<string, string> = {
  IMAGE: '图片',
  NARRATION: '录音',
  MUSIC: '音乐 / 环境声',
  VIDEO_CLIP: '视频片段',
}
function toggleAsset(asset: CreationAsset) {
  if (form.assetIds.includes(asset.assetId))
    form.assetIds = form.assetIds.filter((id) => id !== asset.assetId)
  else form.assetIds = [...form.assetIds, asset.assetId]
}
async function upload(event: Event) {
  const input = event.target as HTMLInputElement,
    file = input.files?.[0]
  input.value = ''
  if (!file) return
  await library.upload(file, uploadKind.value)
  await p.refresh()
}
async function assetAction(action: () => Promise<void>) {
  try {
    await action()
    await p.refresh()
  } catch (reason) {
    error.value = errorText(reason, '素材操作失败')
  }
}
function duration(asset: CreationAsset) {
  const normalized = asset.metadata?.normalized as
    | { format?: { duration?: number | string } }
    | undefined
  return Math.round(Number(normalized?.format?.duration ?? 0) * 1000)
}
function chooseBed(event: Event, ambient: boolean) {
  const id = (event.target as HTMLSelectElement).value,
    asset = readyAssets.value.find((a) => a.assetId === id)
  const bed: AudioBed | null = asset
    ? {
        assetId: id,
        sourceInMs: 0,
        sourceOutMs: Math.min(duration(asset) || form.targetDurationMs, form.targetDurationMs),
        gainDb: ambient ? -24 : -18,
        fadeInMs: 200,
        fadeOutMs: 200,
        ...(!ambient ? { ducking: true } : {}),
      }
    : null
  if (ambient) form.ambience = bed
  else form.backgroundMusic = bed
}
function move(index: number, direction: number) {
  const target = index + direction
  if (target < 0 || target >= scenes.value.length) return
  const next = [...scenes.value]
  ;[next[index], next[target]] = [next[target]!, next[index]!]
  scenes.value = next
  p.changed()
}
function addScene() {
  showSceneEditor.value = true
  const scene: Scene = {
    id: `scene-${creationKey()}`,
    layout: 'TITLE_CARD',
    title: '新场景',
    body: ['请填写屏幕内容'],
    narration: '',
    durationMs: 5000,
    visualAssetId: null,
    sourceInMs: null,
    sourceOutMs: null,
    fit: 'CONTAIN',
    audioMode: form.dialogueMode === 'TTS' ? 'TTS' : 'NO_DIALOGUE',
    narrationAssetId: null,
    cues: [],
    sourceRefs: form.contentKind === 'USER_PROVIDED' ? ['brief:user'] : [],
  }
  scenes.value = [...scenes.value, scene]
  p.changed()
}
function remove(index: number) {
  scenes.value = scenes.value.filter((_, n) => n !== index)
  p.changed()
}
function synchronizeDuration() {
  form.targetDurationMs = totalDuration.value
  showBrief.value = true
}
function switchBoard(event: Event) {
  const selected = boards.value.find(
    (b) => b.storyboardId === (event.target as HTMLSelectElement).value,
  )
  if (selected) p.selectBoard(selected)
}
function refresh() {
  void p.refresh(false, true)
}
</script>

<template>
  <section class="automatic-production" aria-label="自动制作工作台">
    <header class="auto-heading">
      <div>
        <h2>AI 制作</h2>
        <p>确认故事板和费用后，系统准备素材、渲染视频，并继续试映与打包。</p>
      </div>
      <button
        class="creator-secondary-action"
        type="button"
        :disabled="busy || loading"
        @click="refresh"
      >
        刷新状态
      </button>
    </header>
    <p v-if="error" class="auto-error" role="alert">{{ error }}</p>
    <p v-if="notice" class="auto-notice" role="status">{{ notice }}</p>
    <p v-if="loading">正在恢复本条视频的制作状态…</p>
    <p v-else-if="!available" class="auto-empty">
      媒体功能尚未启用。启用媒体存储与制作执行器后，可在这里导入素材和制作视频。
    </p>
    <template v-else>
      <nav class="studio-workspace-switch" aria-label="制作工作面">
        <button
          type="button"
          :aria-pressed="productionView === 'canvas'"
          @click="productionView = 'canvas'"
        >
          简报与故事板</button
        ><button
          type="button"
          :aria-pressed="productionView === 'monitor'"
          @click="productionView = 'monitor'"
        >
          制作动态与素材
        </button>
      </nav>
      <div
        class="studio-production-workspace"
        :class="{ 'show-monitor': productionView === 'monitor' }"
      >
        <div class="studio-production-canvas">
          <section class="auto-brief">
            <button
              type="button"
              class="section-heading"
              :aria-expanded="showBrief"
              @click="showBrief = !showBrief"
            >
              <strong>制作简报{{ brief ? ` · 第 ${brief.revision} 版` : '' }}</strong
              ><span>{{ showBrief ? '收起编辑' : '查看与调整' }}</span>
            </button>
            <dl v-if="!showBrief" class="auto-brief-summary">
              <div>
                <dt>主题</dt>
                <dd>{{ form.topic || '待补充' }}</dd>
              </div>
              <div>
                <dt>受众</dt>
                <dd>{{ form.audience || '待补充' }}</dd>
              </div>
              <div>
                <dt>核心内容</dt>
                <dd :title="form.coreMessage">{{ form.coreMessage || '待补充' }}</dd>
              </div>
              <div>
                <dt>制作设置</dt>
                <dd>{{ form.targetDurationMs / 1000 }} 秒，预算 ¥{{ money(budget) }}</dd>
              </div>
            </dl>
            <form v-if="showBrief" @submit.prevent="p.save">
              <fieldset class="brief-grid" :disabled="locked">
                <label
                  >想讲什么<input
                    v-model="form.topic"
                    required
                    maxlength="200"
                    placeholder="本条视频的主题"
                /></label>
                <label
                  >给谁看<input
                    v-model="form.audience"
                    required
                    maxlength="200"
                    placeholder="例如：第一次接触这个话题的观众"
                /></label>
                <label class="wide"
                  >希望观众记住什么<textarea
                    v-model="form.coreMessage"
                    required
                    maxlength="500"
                    rows="2"
                    placeholder="说明核心信息；已有稿件会作为真实材料继承"
                  ></textarea>
                </label>
                <details class="auto-brief-options wide">
                  <summary>
                    时长、配音与素材设置
                    <span>{{ form.targetDurationMs / 1000 }} 秒，预算 ¥{{ money(budget) }}</span>
                  </summary>
                  <div class="brief-grid">
                    <label
                      >目标时长（秒）<input
                        :value="form.targetDurationMs / 1000"
                        type="number"
                        min="1"
                        max="180"
                        step="0.001"
                        required
                        @input="
                          form.targetDurationMs = Math.round(
                            Number(($event.target as HTMLInputElement).value) * 1000,
                          )
                        "
                    /></label>
                    <label
                      >声音来源<select v-model="form.dialogueMode">
                        <option value="TTS">AI 配音</option>
                        <option value="USER_NARRATION">自己的录音</option>
                        <option value="SOURCE">视频片段原声</option>
                        <option value="NO_DIALOGUE">无对白</option>
                        <option value="MIXED">按场景分别选择</option>
                      </select></label
                    >
                    <label
                      >内容依据<select v-model="form.contentKind">
                        <option value="USER_PROVIDED">使用我提供的内容</option>
                        <option value="FICTION">虚构内容</option>
                      </select></label
                    >
                    <label
                      >字幕字号<input
                        v-model.number="form.subtitleFontSize"
                        type="number"
                        min="24"
                        max="60"
                        placeholder="默认 36" /></label
                    ><label
                      >本条视频制作预算（元）<input
                        v-model.number="budget"
                        type="number"
                        min="0.000001"
                        max="999999999999"
                        step="0.01"
                        required
                    /></label>
                    <label class="wide"
                      >其他要求<textarea
                        v-model="form.constraints"
                        maxlength="2000"
                        rows="2"
                        placeholder="例如：画面用图文卡片，或需要哪些 AI 视频镜头；不要添加哪些内容"
                      ></textarea>
                    </label>
                    <div class="wide">
                      <strong>本期可用素材</strong>
                      <div class="asset-choices">
                        <label v-for="asset in readyAssets" :key="asset.assetId"
                          ><input
                            type="checkbox"
                            :checked="form.assetIds.includes(asset.assetId)"
                            @change="toggleAsset(asset)"
                          />{{ asset.fileName }} <small>{{ assetKind[asset.kind] }}</small></label
                        >
                        <p v-if="!readyAssets.length">
                          还没有素材，可在下方导入；纯文字图文不需要上传图片。
                        </p>
                      </div>
                    </div>
                    <label
                      >背景音乐<select
                        :value="form.backgroundMusic?.assetId ?? ''"
                        @change="chooseBed($event, false)"
                      >
                        <option value="">不添加</option>
                        <option
                          v-for="asset in readyAssets.filter(
                            (a) => a.kind === 'MUSIC' && form.assetIds.includes(a.assetId),
                          )"
                          :key="asset.assetId"
                          :value="asset.assetId"
                        >
                          {{ asset.fileName }}
                        </option>
                      </select></label
                    >
                    <label
                      >环境声<select
                        :value="form.ambience?.assetId ?? ''"
                        @change="chooseBed($event, true)"
                      >
                        <option value="">不添加</option>
                        <option
                          v-for="asset in readyAssets.filter(
                            (a) => a.kind === 'MUSIC' && form.assetIds.includes(a.assetId),
                          )"
                          :key="asset.assetId"
                          :value="asset.assetId"
                        >
                          {{ asset.fileName }}
                        </option>
                      </select></label
                    >
                    <template v-for="(bed, n) in [form.backgroundMusic, form.ambience]" :key="n"
                      ><div v-if="bed" class="wide audio-settings">
                        <strong>{{ n === 0 ? '音乐' : '环境声' }}</strong
                        ><label
                          >起点（秒）<input
                            :value="bed.sourceInMs / 1000"
                            type="number"
                            min="0"
                            step="0.001"
                            @input="
                              bed.sourceInMs = Math.round(
                                Number(($event.target as HTMLInputElement).value) * 1000,
                              )
                            " /></label
                        ><label
                          >终点（秒）<input
                            :value="bed.sourceOutMs / 1000"
                            type="number"
                            min="0.001"
                            step="0.001"
                            @input="
                              bed.sourceOutMs = Math.round(
                                Number(($event.target as HTMLInputElement).value) * 1000,
                              )
                            " /></label
                        ><label
                          >音量（dB）<input
                            v-model.number="bed.gainDb"
                            type="number"
                            min="-36"
                            max="0"
                            step="0.1" /></label
                        ><label
                          >淡入（毫秒）<input
                            v-model.number="bed.fadeInMs"
                            type="number"
                            min="0"
                            max="10000" /></label
                        ><label
                          >淡出（毫秒）<input
                            v-model.number="bed.fadeOutMs"
                            type="number"
                            min="0"
                            max="10000" /></label
                        ><label v-if="n === 0"
                          ><input v-model="bed.ducking" type="checkbox" />对白时压低音乐</label
                        >
                      </div></template
                    >
                  </div>
                </details>
              </fieldset>
              <div class="auto-actions">
                <button class="creator-secondary-action" type="submit" :disabled="locked">
                  保存简报与预算</button
                ><button
                  type="button"
                  class="creator-primary-button"
                  :disabled="
                    locked ||
                    !form.topic.trim() ||
                    !form.coreMessage.trim() ||
                    !form.audience.trim()
                  "
                  @click="p.estimatePrepare"
                >
                  保存并查看故事板准备费用</button
                ><span v-if="briefDirty" class="muted">有未保存的简报修改</span>
              </div>
            </form>
          </section>
          <section v-if="preparationQuote" class="confirmation" aria-label="故事板准备确认">
            <h3>准备故事板</h3>
            <p>
              本次仅准备脚本与场景，不制作视频。模型：{{ preparationQuote.model }}；配置价格估算 ¥{{
                money(preparationQuote.estimatedMaxAdditionalCostCny)
              }}。
            </p>
            <label
              >本次准备上限（元）<input
                v-model.number="preparationLimit"
                type="number"
                :min="Number(preparationQuote.estimatedMaxAdditionalCostCny)"
                step="0.000001" /></label
            ><button
              class="creator-primary-button"
              type="button"
              :disabled="
                busy ||
                briefDirty ||
                preparationLimit < Number(preparationQuote.estimatedMaxAdditionalCostCny)
              "
              @click="p.confirmPrepare"
            >
              确认准备故事板
            </button>
          </section>
          <section v-if="board" class="auto-storyboard">
            <div class="section-heading">
              <strong>故事板</strong
              ><select
                :value="board.storyboardId"
                :disabled="locked || dirty"
                aria-label="故事板版本"
                @change="switchBoard"
              >
                <option v-for="item in boards" :key="item.storyboardId" :value="item.storyboardId">
                  第 {{ item.revision }} 版{{
                    item.storyboardId === board.storyboardId ? '（当前查看）' : ''
                  }}
                </option>
              </select>
            </div>
            <p class="muted">
              {{ scenes.length }} 个场景，共
              {{ (totalDuration / 1000).toFixed(2) }} 秒。保存修改会新建版本。
            </p>
            <div v-if="!showSceneEditor" class="auto-scene-overview" aria-label="故事板场景概览">
              <article v-for="(scene, index) in scenes" :key="scene.id">
                <span>镜头 {{ index + 1 }}</span
                ><strong>{{ scene.title || '未命名场景' }}</strong
                ><small>{{ (scene.durationMs / 1000).toFixed(1) }} 秒</small>
              </article>
            </div>
            <button
              type="button"
              class="creator-secondary-action"
              :aria-expanded="showSceneEditor"
              @click="showSceneEditor = !showSceneEditor"
            >
              {{ showSceneEditor ? '收起场景编辑' : '编辑故事板' }}
            </button>
            <div v-if="showSceneEditor" class="auto-scene-editors">
              <CreationSceneEditor
                v-for="(scene, index) in scenes"
                :key="scene.id"
                v-model="scenes[index]!"
                :task-id="selectedTaskId"
                :assets="readyAssets"
                :disabled="locked"
                :index="index"
                :total="scenes.length"
                @change="p.changed"
                @audio="form.dialogueMode = 'MIXED'"
                @move="move(index, $event)"
                @remove="remove(index)"
                @subtitle="p.importSrt(scene.id, $event)"
              />
            </div>
            <div class="auto-actions">
              <button
                class="creator-secondary-action"
                type="button"
                :disabled="locked || scenes.length >= 48"
                @click="addScene"
              >
                添加场景</button
              ><button
                class="creator-secondary-action"
                type="button"
                :disabled="locked || !dirty || briefDirty"
                @click="p.saveScenes"
              >
                保存场景新版本</button
              ><button
                class="creator-secondary-action"
                v-if="totalDuration !== form.targetDurationMs"
                type="button"
                :disabled="locked"
                @click="synchronizeDuration"
              >
                将目标时长改为 {{ (totalDuration / 1000).toFixed(2) }} 秒</button
              ><button
                class="creator-secondary-action"
                type="button"
                :disabled="busy || dirty"
                @click="p.checkTiming"
              >
                查看时长建议
              </button>
            </div>
            <div v-if="board.check.problems.length" class="auto-problems">
              <strong>制作前仍需准备</strong>
              <ul>
                <li v-for="(problem, index) in board.check.problems" :key="index">
                  {{ problem.message }}
                </li>
              </ul>
              <p>AI 配音和 AI 画面会在确认的范围内自动准备，其余问题需要先处理。</p>
            </div>
            <div v-if="timing">
              <p v-if="!timing.proposals.length">没有需要调整的时长建议。</p>
              <ul>
                <li v-for="(proposal, index) in timing.proposals" :key="index">
                  {{ proposal.message }}
                </li>
              </ul>
              <button
                class="creator-secondary-action"
                v-if="
                  timing.proposals.some((t) => ['EXTEND_SCENE', 'SHORTEN_SCENE'].includes(t.code))
                "
                type="button"
                :disabled="locked"
                @click="p.acceptTiming"
              >
                采纳这些时长建议并保存新版本
              </button>
            </div>
            <div class="make-actions">
              <div>
                <strong>先看一段，或直接制作整片</strong>
                <p>样片只准备覆盖场景。整片会继续预处理、完整试映和打包；实际投稿由你完成。</p>
              </div>
              <button
                class="creator-secondary-action"
                type="button"
                :disabled="locked || dirty || briefDirty"
                @click="p.estimate('SAMPLE')"
              >
                查看样片范围</button
              ><button
                type="button"
                class="creator-primary-button"
                :disabled="locked || dirty || briefDirty"
                @click="p.estimate('FULL')"
              >
                查看整片范围
              </button>
            </div>
          </section>
          <section v-if="quote" class="confirmation" aria-label="制作范围确认">
            <h3>{{ `制作${kindLabel(quote.kind)}` }}</h3>
            <p>
              {{ quote.sceneIds.length }} 个场景，实际范围约
              {{ (quote.durationMs / 1000).toFixed(2) }} 秒；需准备配音
              {{ quote.voiceSceneIds.length }} 个场景、AI 画面
              {{ quote.videoSceneIds.length }} 个场景。
            </p>
            <p>
              素材准备估算 ¥{{ money(quote.preparationEstimateCny) }}，整片试映估算 ¥{{
                money(quote.reviewEstimateCny)
              }}。估算依据配置价格，实际费用按流水记录。
            </p>
            <ul v-if="quote.blockers.length" class="auto-error">
              <li v-for="reason in quote.blockers" :key="reason">{{ reason }}</li>
            </ul>
            <label
              >本次合计新增费用上限（元）<input
                v-model.number="executionLimit"
                type="number"
                :min="Number(quote.estimatedMaxAdditionalCostCny)"
                step="0.000001"
            /></label>
            <p class="muted">
              只允许本次所选范围内的配音、AI 镜头和制作步骤。需要增加费用或处理材料冲突时会停止。
            </p>
            <button
              type="button"
              class="creator-primary-button"
              :disabled="
                locked ||
                quote.blockers.length > 0 ||
                executionLimit < Number(quote.estimatedMaxAdditionalCostCny)
              "
              @click="p.confirm"
            >
              确认{{ `制作${kindLabel(quote.kind)}` }}
            </button>
          </section>
          <section v-if="board" class="revision-panel">
            <div class="section-heading">
              <strong>修改当前故事板</strong><span>先看影响，再保存新版本</span>
            </div>
            <label
              >想改哪里<textarea
                v-model="instruction"
                maxlength="2000"
                rows="3"
                :disabled="locked"
                placeholder="例如：第三幕旁白改为“更清楚的解释”，字幕大一点。换图时请填写素材完整文件名。"
              ></textarea>
            </label>
            <div class="auto-actions">
              <button
                class="creator-secondary-action"
                type="button"
                :disabled="locked || dirty || briefDirty || !instruction.trim()"
                @click="p.propose"
              >
                预览修改提案</button
              ><button
                class="creator-secondary-action"
                v-if="currentDraftVideo && board.parentStoryboardId"
                type="button"
                :disabled="locked || dirty || briefDirty"
                @click="p.estimate('REVISION')"
              >
                查看修改版制作范围
              </button>
            </div>
            <template v-if="proposal"
              ><ul v-if="proposal.clarifications.length" class="auto-error">
                <li v-for="question in proposal.clarifications" :key="question">{{ question }}</li>
              </ul>
              <template v-else
                ><div class="revision-differences">
                  <p v-for="(diff, index) in proposal.differences" :key="index">
                    <strong
                      >{{
                        diff.sceneId
                          ? `场景 ${scenes.findIndex((s) => s.id === diff.sceneId) + 1}`
                          : '整片'
                      }}
                      · {{ fieldLabels[diff.field] ?? '音轨区间' }}</strong
                    ><span>{{ differenceText(diff.before, diff.field) }}</span
                    ><b aria-label="变更为">→</b
                    ><span>{{ differenceText(diff.after, diff.field) }}</span>
                  </p>
                </div>
                <p v-if="proposal.plan">
                  需要准备配音 {{ proposal.plan.voiceSceneIds.length }} 个场景、AI 画面
                  {{ proposal.plan.videoSceneIds.length }} 个场景；可复用
                  {{ proposal.plan.reusedAssetIds.length }} 份素材。{{
                    proposal.plan.rebuildTimeline ? '将按新场景顺序与时长重建时间线。' : ''
                  }}素材准备估算：{{
                    proposal.plan.estimatedPreparationCny == null
                      ? '待配置完整后估算'
                      : `¥${money(proposal.plan.estimatedPreparationCny)}`
                  }}。
                </p>
                <ul v-if="proposal.plan" class="muted">
                  <li v-for="note in proposal.plan.notes" :key="note">{{ note }}</li>
                </ul>
                <button
                  type="button"
                  class="creator-primary-button"
                  :disabled="locked || !proposal.proposalId"
                  @click="p.acceptRevision"
                >
                  确认这些修改并保存新版本
                </button></template
              ></template
            >
          </section>
          <CreationVersionCompare :task-id="selectedTaskId" :executions="executions" />
        </div>
        <aside class="studio-production-monitor" aria-label="制作动态与素材">
          <div class="studio-monitor-heading">
            <span class="studio-live-mark" aria-hidden="true"></span><strong>制作动态</strong>
          </div>
          <CreatorStageState
            v-if="!board && !executions.length"
            state="waiting"
            :title="briefComplete ? '制作方向已带入，确认后开始准备' : '先确认这条视频要讲什么'"
            :description="
              briefComplete
                ? '主题、受众与核心信息已准备好。先核对简报，再查看故事板准备费用。'
                : '简报会沿用发布方案；缺少的内容可以在“制作简报”中补充，其他参数按需调整。'
            "
          >
            <template #actions>
              <button
                type="button"
                :class="briefComplete ? 'creator-secondary-action' : 'creator-primary-button'"
                @click="openBriefEditor"
              >
                核对简报
              </button>
              <button
                v-if="briefComplete && !showBrief"
                type="button"
                class="creator-primary-button"
                :disabled="locked"
                @click="p.estimatePrepare"
              >
                查看故事板准备费用
              </button>
            </template>
          </CreatorStageState>
          <div v-if="runtime?.budget" class="auto-budget">
            <span
              >制作预算 <strong>¥{{ money(runtime.budget.limitCny) }}</strong></span
            ><span>已知 ¥{{ money(runtime.budget.knownCny) }}</span
            ><span>预留 ¥{{ money(runtime.budget.reservedCny) }}</span
            ><span>待核实 ¥{{ money(runtime.budget.unknownCny) }}</span
            ><span>可用 ¥{{ money(runtime.budget.remainingCny) }}</span>
          </div>
          <section v-if="executions.length" class="auto-results">
            <div class="section-heading">
              <strong>执行进度与成果</strong>
              <button
                v-if="executions.length > 1"
                type="button"
                class="creator-ghost-button"
                @click="showExecutionHistory = !showExecutionHistory"
              >
                {{ showExecutionHistory ? '收起历史' : '查看全部记录' }}
              </button>
            </div>
            <article
              v-for="execution in visibleExecutions"
              :key="execution.executionId"
              class="auto-run"
            >
              <div class="run-heading">
                <strong>{{ kindLabel(execution.kind) }} · {{ label(execution.status) }}</strong
                ><span>{{ label(execution.run?.stage ?? execution.stage) }}</span>
              </div>
              <p>
                本次已知 ¥{{ money(execution.costs.knownCny) }}，预留 ¥{{
                  money(execution.costs.reservedCny)
                }}，待核实 ¥{{ money(execution.costs.unknownCny) }}；限额 ¥{{
                  money(execution.maxAdditionalCostCny)
                }}。
              </p>
              <p v-if="execution.message" class="auto-error" role="status">
                {{ execution.message }}
              </p>
              <div class="auto-actions">
                <button
                  class="creator-secondary-action"
                  v-if="!['COMPLETED', 'CANCELLED'].includes(execution.status)"
                  type="button"
                  :disabled="busy"
                  @click="p.cancel(execution)"
                >
                  {{
                    execution.status === 'WAITING_USER'
                      ? '结束本次制作，再调整内容'
                      : '停止本次制作'
                  }}</button
                ><template v-if="execution.previewAvailable"
                  ><button
                    class="creator-secondary-action"
                    type="button"
                    :disabled="busy"
                    @click="p.showPreview(execution)"
                  >
                    播放{{ execution.kind === 'SAMPLE' ? '样片' : '制作预览' }}</button
                  ><button
                    class="creator-secondary-action"
                    type="button"
                    :disabled="busy"
                    @click="p.download(execution, 'video.mp4')"
                  >
                    下载 MP4</button
                  ><button
                    class="creator-secondary-action"
                    type="button"
                    :disabled="busy"
                    @click="p.download(execution, 'subtitles.srt')"
                  >
                    下载字幕</button
                  ><button
                    class="creator-secondary-action"
                    type="button"
                    :disabled="busy"
                    @click="p.download(execution, 'cover.png')"
                  >
                    下载封面
                  </button></template
                ><button
                  v-if="execution.packageAvailable"
                  class="creator-primary-button"
                  type="button"
                  :disabled="busy"
                  @click="p.download(execution, 'publish-package.zip')"
                >
                  下载正式发布包
                </button>
              </div>
              <p
                v-if="execution.kind === 'SAMPLE' && execution.status === 'COMPLETED'"
                class="muted"
              >
                这是样片。满意后可在上方选择整片范围，已准备且仍适用的素材会复用。
              </p>
            </article>
            <div v-if="preview" class="auto-player">
              <video
                :key="preview.url"
                :src="preview.url"
                controls
                preload="metadata"
                @play="p.refreshPreview"
                @error="p.refreshPreview"
              ></video
              ><button
                class="creator-secondary-action"
                type="button"
                :disabled="busy"
                @click="
                  p.showPreview(executions.find((e) => e.executionId === preview!.executionId)!)
                "
              >
                刷新播放地址
              </button>
            </div>
          </section>
          <div
            v-if="preparation && !['COMPLETED', 'CANCELLED'].includes(preparation.run.status)"
            class="auto-run"
          >
            <strong>故事板准备：{{ label(preparation.run.status) }}</strong>
            <p v-if="preparation.run.status === 'FAILED'">
              准备失败，已保留费用记录。核对材料或配置后可重新估算。
            </p>
            <button
              class="creator-secondary-action"
              v-if="!['FAILED', 'CANCELLED'].includes(preparation.run.status)"
              type="button"
              :disabled="busy"
              @click="p.cancelPreparation"
            >
              停止准备
            </button>
          </div>
          <details class="auto-library">
            <summary class="section-heading">
              <strong>素材库</strong
              ><span>{{ library.assets.value.length }} 份素材，按需导入图片、录音或视频</span>
            </summary>
            <div class="auto-actions">
              <select
                v-model="uploadKind"
                aria-label="导入素材类型"
                :disabled="library.busy.value || locked"
              >
                <option value="IMAGE">图片</option>
                <option value="NARRATION">自己的录音</option>
                <option value="MUSIC">音乐 / 环境声</option>
                <option value="VIDEO_CLIP">视频片段</option></select
              ><button
                class="creator-secondary-action"
                type="button"
                :disabled="library.busy.value || locked"
                @click="fileInput?.click()"
              >
                导入素材 / 继续上传</button
              ><input
                ref="fileInput"
                class="hidden-file"
                type="file"
                :accept="
                  uploadKind === 'IMAGE'
                    ? 'image/png,image/jpeg'
                    : uploadKind === 'VIDEO_CLIP'
                      ? '.mp4,.mov'
                      : '.wav,.mp3,.m4a'
                "
                @change="upload"
              /><button
                class="creator-secondary-action"
                v-if="library.busy.value"
                type="button"
                @click="library.pause"
              >
                暂停上传
              </button>
            </div>
            <p v-if="library.progress.value" role="status">{{ library.progress.value }}</p>
            <p v-if="library.error.value" role="alert" class="auto-error">
              {{ library.error.value }}
            </p>
            <p class="muted">
              刷新后重新选择同一个文件可继续未完成的上传。完整成片请使用原有的成片上传入口。
            </p>
            <ul class="asset-list">
              <li v-for="asset in library.assets.value" :key="asset.assetId">
                <div>
                  <strong>{{ asset.fileName }}</strong
                  ><small
                    >{{ assetKind[asset.kind] }}，{{
                      (asset.fileSize / 1024 / 1024).toFixed(2)
                    }}
                    MiB</small
                  >
                  <p v-if="asset.failureMessage" class="auto-error">{{ asset.failureMessage }}</p>
                </div>
                <span>{{ label(asset.status) }}{{ asset.cleanupPending ? '，等待清理' : '' }}</span
                ><button
                  class="creator-secondary-action"
                  v-if="asset.status === 'READY'"
                  type="button"
                  :disabled="locked"
                  @click="deleteAsset(asset)"
                >
                  查看删除影响</button
                ><button
                  class="creator-secondary-action"
                  v-if="asset.status === 'FAILED'"
                  type="button"
                  :disabled="locked"
                  @click="assetAction(() => library.retry(asset))"
                >
                  重试准备</button
                ><button
                  class="creator-secondary-action"
                  v-if="!['READY', 'CANCELLED'].includes(asset.status)"
                  type="button"
                  :disabled="locked"
                  @click="assetAction(() => library.cancel(asset))"
                >
                  取消并清理
                </button>
              </li>
            </ul>
          </details>
        </aside>
      </div>
    </template>
  </section>
  <CreationProviderSettings @changed="refresh" />
</template>
