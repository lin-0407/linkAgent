import { computed, onBeforeUnmount, reactive, ref, watch, type Ref } from 'vue'
import * as api from '@/api/creationProduction'
import { getMediaFeatureStatus } from '@/api/media'
import { errorText } from '@/utils/error'
import type { Brief, BriefDocument, Storyboard, Scene, Runtime, Preparation, Execution, ExecutionEstimate, Timing, RevisionProposal } from '@/types/creationProduction'
import { useCreationAssets } from './useCreationAssets'

const blank = (): BriefDocument => ({ topic: '', coreMessage: '', audience: '', targetDurationMs: 90000, dialogueMode: 'TTS', contentKind: 'USER_PROVIDED', assetIds: [], constraints: '', backgroundMusic: null, ambience: null, subtitleFontSize: null })
const copy = <T>(value: T): T => JSON.parse(JSON.stringify(value))
const active = (status: string) => ['ACTIVE','CANCEL_REQUESTED'].includes(status)
const pendingRun = (status: string) => ['QUEUED','RUNNING','WAITING_DEPENDENCY','CANCEL_REQUESTED'].includes(status)

export function useAutomaticProduction(taskId: Ref<string>, onFullChanged: () => void, seed: () => Partial<BriefDocument> = () => ({})) {
  const form = reactive(blank())
  const available = ref(true), loading = ref(false), busy = ref(false), error = ref(''), notice = ref('')
  const runtime = ref<Runtime | null>(null), brief = ref<Brief | null>(null), boards = ref<Storyboard[]>([])
  const board = ref<Storyboard | null>(null), scenes = ref<Scene[]>([]), dirty = ref(false), budget = ref(20)
  const preparation = ref<Preparation | null>(null), preparationQuote = ref<{ estimatedMaxAdditionalCostCny: number; model: string } | null>(null)
  const preparationLimit = ref(0), preparationRevision = ref(0), quote = ref<ExecutionEstimate | null>(null), executionLimit = ref(0)
  const executions = ref<Execution[]>([]), timing = ref<Timing | null>(null)
  const instruction = ref(''), proposal = ref<RevisionProposal | null>(null)
  const preview = ref<{ url: string; expiresAt: string; executionId: string } | null>(null)
  const library = useCreationAssets(taskId)
  let generation = 0, loadVersion = 0, disposed = false, initialized = false, refreshing = false, failures = 0
  let timer: ReturnType<typeof setTimeout> | null = null, followLatest = true, lastFull = ''
  let savedDocument = ''
  const locked = computed(() => busy.value || executions.value.some(e => !['COMPLETED','CANCELLED'].includes(e.status)) || !!preparation.value && pendingRun(preparation.value.run.status))
  const briefDirty = computed(() => !brief.value || JSON.stringify(form) !== savedDocument)
  const readyAssets = computed(() => library.assets.value.filter(a => a.status === 'READY'))
  const activeExecution = computed(() => executions.value.find(e => !['COMPLETED','CANCELLED'].includes(e.status)) ?? null)
  const current = (task: string, token: number) => !disposed && task === taskId.value && token === generation
  const resetQuotes = () => { quote.value = null;preparationQuote.value = null }

  function selectBoard(value: Storyboard, follow = false) {
    if (dirty.value && !window.confirm('场景有未保存的修改。切换后将放弃这些修改，确定继续吗？')) return
    board.value = value;scenes.value = copy(value.document.candidate.scenes);dirty.value = false;followLatest = follow;quote.value = null;timing.value = null;proposal.value = null
  }
  function fillBrief(value: Brief) {
    Object.assign(form, blank(), copy(value.document));savedDocument = JSON.stringify(form)
  }
  function schedule() {
    if (timer) clearTimeout(timer)
    const pendingAssets = library.assets.value.some(a => ['CREATING','CREATED','UPLOADING','UPLOADED','VERIFYING','PREPARING','CANCELLING'].includes(a.status) || a.cleanupPending)
    if (!disposed && failures < 3 && (executions.value.some(e => active(e.status)) || !!preparation.value && pendingRun(preparation.value.run.status) || pendingAssets)) {
      timer = setTimeout(() => { void refresh(false) }, 2500)
    }
  }
  async function refresh(initial = false, force = false) {
    const task = taskId.value, token = generation
    if (!task || disposed || refreshing && !force) return
    const requestVersion = ++loadVersion
    refreshing = true
    if (initial) loading.value = true
    try {
      const feature = await getMediaFeatureStatus()
      if (!current(task, token) || requestVersion !== loadVersion) return
      available.value = feature.enabled
      if (!feature.enabled) return
      const result = await Promise.all([api.readRuntime(task), api.listBriefs(task), api.listStoryboards(task), api.listExecutions(task), api.currentPreparation(task), library.refresh()])
      if (!current(task, token) || requestVersion !== loadVersion) return
      runtime.value = result[0];boards.value = result[2];executions.value = result[3];preparation.value = result[4];failures = 0
      const latestBrief = result[1][0] ?? null
      const untouched = !initialized || !briefDirty.value, previousBriefId = brief.value?.briefId
      brief.value = latestBrief
      if (!latestBrief && !initialized) Object.assign(form, blank(), seed())
      if (latestBrief && untouched && (!initialized || previousBriefId !== latestBrief.briefId)) fillBrief(latestBrief)
      if (!initialized && result[0].budget) budget.value = Number(result[0].budget.limitCny)
      initialized = true
      const latest = result[2].find(b => b.briefId === latestBrief?.briefId) ?? null
      if (latest && !dirty.value && (followLatest || !board.value)) {
        if (board.value?.storyboardId !== latest.storyboardId) selectBoard(latest, true)
        else board.value = latest
      }
      const completed = result[3].find(e => e.kind !== 'SAMPLE' && e.status === 'COMPLETED')
      if (completed && completed.executionId !== lastFull) { lastFull = completed.executionId;onFullChanged() }
    } catch (reason) {
      if (current(task, token) && requestVersion === loadVersion) { failures++;error.value = errorText(reason, '制作状态读取失败，请点击刷新重试') }
    } finally {
      if (current(task, token) && requestVersion === loadVersion) { refreshing = false;loading.value = false;schedule() }
    }
  }
  async function perform(action: (task: string, check: () => void) => Promise<void>, fallback: string) {
    if (busy.value || !taskId.value) return
    const task = taskId.value, token = generation
    const check = () => { if (!current(task, token)) throw new DOMException('任务已切换','AbortError') }
    busy.value = true;loadVersion++;error.value = '';notice.value = ''
    try { await action(task, check);check();failures = 0;await refresh(false, true) }
    catch (reason) { if (current(task, token)) { error.value = errorText(reason, fallback);await refresh(false, true) } }
    finally { if (current(task, token)) busy.value = false }
  }
  function key(task: string, action: string, payload: unknown) {
    const identity = `${task}:${action}`, fingerprint = JSON.stringify(payload)
    try {
      const stored = JSON.parse(sessionStorage.getItem(`creation-request:${identity}`) || 'null')
      if (stored?.fingerprint === fingerprint && typeof stored?.key === 'string') return stored.key as string
      const next = api.creationKey();sessionStorage.setItem(`creation-request:${identity}`, JSON.stringify({ fingerprint, key: next }));return next
    } catch { return api.creationKey() }
  }
  async function saveBriefInside(task: string, check: () => void) {
    let snapshot = await api.readRuntime(task);check()
    if (!snapshot.initialized) snapshot = await api.initializeBudget(task, snapshot.workspaceRevision, Number(budget.value), key(task, 'budget', [snapshot.workspaceRevision, budget.value]))
    else if (Number(snapshot.budget?.limitCny) !== Number(budget.value)) snapshot = await api.updateCreationBudget(task, snapshot.workspaceRevision, Number(budget.value))
    check();runtime.value = snapshot
    if (!brief.value || briefDirty.value) {
      const document = copy(form)
      if (!Number(document.subtitleFontSize)) delete document.subtitleFontSize
      const saved = await api.saveBrief(task, snapshot.workspaceRevision, document, key(task, 'brief', [snapshot.workspaceRevision, document]));check()
      brief.value = saved;fillBrief(saved);runtime.value = await api.readRuntime(task);check()
    }
  }
  const save = () => perform(async (task, check) => { if (locked.value && !busy.value) return;await saveBriefInside(task, check);notice.value = '简报与制作预算已保存' }, '简报保存失败')
  const estimatePrepare = () => perform(async (task, check) => {
    await saveBriefInside(task, check)
    const revision = runtime.value!.workspaceRevision, briefId = brief.value!.briefId
    const result = await api.estimatePreparation(task, revision, briefId);check()
    preparationQuote.value = result;preparationRevision.value = revision;preparationLimit.value = Number(result.estimatedMaxAdditionalCostCny)
  }, '准备费用估算失败')
  const confirmPrepare = () => perform(async (task, check) => {
    if (!preparationQuote.value || !brief.value || briefDirty.value) throw new Error('简报已修改，请重新查看准备费用')
    const payload = [preparationRevision.value, brief.value.briefId, preparationLimit.value]
    const result = await api.prepareStoryboard(task, preparationRevision.value, brief.value.briefId, Number(preparationLimit.value), key(task, 'prepare', payload));check()
    preparation.value = result;preparationQuote.value = null;followLatest = true;notice.value = '已确认准备故事板，可离开页面后回来继续'
  }, '故事板准备受理失败，重试将复用原请求')
  const saveScenes = () => perform(async (task, check) => {
    if (!board.value || !brief.value) return
    if (briefDirty.value) throw new Error('请先保存简报，再保存场景版本')
    const snapshot = await api.readRuntime(task);check()
    const payload = [snapshot.workspaceRevision, brief.value.briefId, board.value.storyboardId, scenes.value]
    const result = await api.saveStoryboard(task, snapshot.workspaceRevision, brief.value.briefId, board.value.storyboardId, copy(scenes.value), key(task, 'board', payload));check()
    dirty.value = false;selectBoard(result, true);notice.value = '已保存新故事板，旧版本保留'
  }, '场景保存失败')
  const estimate = (kind: Execution['kind']) => perform(async (task, check) => {
    if (!board.value || dirty.value || briefDirty.value) throw new Error('请先保存简报和场景修改')
    if (runtime.value?.budget && Number(runtime.value.budget.limitCny) !== Number(budget.value)) throw new Error('制作预算有未保存的调整，请先保存简报与预算')
    const snapshot = await api.readRuntime(task);check()
    const result = await api.estimateExecution(task, snapshot.workspaceRevision, board.value.storyboardId, kind);check()
    runtime.value = snapshot;quote.value = result;executionLimit.value = Number(result.estimatedMaxAdditionalCostCny)
  }, '制作范围和费用读取失败')
  const confirm = () => perform(async (task, check) => {
    if (!quote.value || dirty.value || briefDirty.value) throw new Error('内容已变化，请重新查看制作范围')
    const result = await api.startExecution(task, quote.value, Number(executionLimit.value), key(task, 'execution', [quote.value, executionLimit.value]));check()
    executions.value = [result, ...executions.value.filter(e => e.executionId !== result.executionId)];quote.value = null
    notice.value = '本次范围已确认，系统会在限额内连续执行；遇到需要处理的问题会停止'
  }, '制作受理失败，重试将复用原确认')
  const cancel = (execution: Execution) => perform(async (task, check) => { await api.cancelExecution(task, execution.executionId);check();notice.value = '已请求结束本次制作。已发送的调用仍会按真实费用结算' }, '制作取消失败')
  const cancelPreparation = () => perform(async (task, check) => { if (preparation.value) await api.cancelCreationRun(task, preparation.value.run.runId);check() }, '准备取消失败')
  const showPreview = (execution: Execution) => perform(async (task, check) => {
    const result = await api.executionDownload(task, execution.executionId, 'video.mp4');check()
    preview.value = { ...result, executionId: execution.executionId }
  }, '视频读取失败')
  const download = (execution: Execution, fileName: string) => perform(async (task, check) => {
    const signed = await api.executionDownload(task, execution.executionId, fileName);check()
    const response = await fetch(signed.url, { credentials: 'omit' });check()
    if (!response.ok) throw new Error('下载地址已失效，请重新点击下载')
    const blob = await response.blob();check()
    const url = URL.createObjectURL(blob), link = document.createElement('a')
    link.href = url;link.download = `${execution.kind === 'SAMPLE' ? '样片' : execution.versionId ?? '成片'}-${fileName}`;link.click()
    setTimeout(() => URL.revokeObjectURL(url), 1000)
  }, '文件下载失败，请检查对象存储的浏览器访问设置')
  const refreshPreview = () => {
    const currentPreview = preview.value
    if (!currentPreview || Date.parse(currentPreview.expiresAt) > Date.now() + 10000) return
    const execution = executions.value.find(e => e.executionId === currentPreview.executionId)
    if (execution) void showPreview(execution)
  }
  const checkTiming = () => perform(async (task, check) => { if (!board.value) return;const result = await api.readTiming(task, board.value.storyboardId);check();timing.value = result }, '时长建议读取失败')
  const acceptTiming = () => perform(async (task, check) => {
    if (!board.value || !timing.value) return
    if (dirty.value || briefDirty.value) throw new Error('请先保存简报与场景，再采纳时长建议')
    const adjustments = timing.value.proposals.filter(p => ['EXTEND_SCENE','SHORTEN_SCENE'].includes(p.code) && p.proposedDurationMs != null).map(p => ({ sceneId: p.sceneId, proposalCode: p.code, durationMs: p.proposedDurationMs! }))
    if (!adjustments.length) throw new Error('当前没有可以直接采纳的时长建议，请按提示调整材料')
    const snapshot = await api.readRuntime(task);check()
    const result = await api.applyTiming(task, board.value.storyboardId, snapshot.workspaceRevision, adjustments, key(task, 'timing', [board.value.storyboardId, snapshot.workspaceRevision, adjustments]));check()
    dirty.value = false;selectBoard(result.board, true);brief.value = null;savedDocument = '';initialized = false
  }, '时长建议采纳失败')
  const importSrt = (sceneId: string, file: File) => perform(async (task, check) => {
    if (!board.value || file.size > 65536) throw new Error('请选择不超过 64 KiB 的 SRT 文件')
    if (dirty.value || briefDirty.value) throw new Error('请先保存简报与场景，再导入本场景的字幕')
    const srt = await file.text();check();const snapshot = await api.readRuntime(task);check()
    const result = await api.importSubtitles(task, board.value.storyboardId, snapshot.workspaceRevision, sceneId, srt, key(task, 'subtitles', [snapshot.workspaceRevision, sceneId, srt]));check()
    dirty.value = false;selectBoard(result, true)
  }, '字幕导入失败')
  const propose = () => perform(async (task, check) => {
    if (!board.value || dirty.value || briefDirty.value) throw new Error('请先保存当前简报和场景，再预览修改')
    const snapshot = await api.readRuntime(task);check()
    const payload = [snapshot.workspaceRevision, board.value.storyboardId, instruction.value]
    const result = await api.proposeRevision(task, snapshot.workspaceRevision, board.value.storyboardId, instruction.value, key(task, 'revision', payload));check();proposal.value = result
  }, '修改提案生成失败')
  const acceptRevision = () => perform(async (task, check) => {
    if (!proposal.value?.proposalId || proposal.value.clarifications.length) return
    const result = await api.applyRevision(task, proposal.value);check()
    dirty.value = false;selectBoard(result, true);proposal.value = null;brief.value = null;savedDocument = '';initialized = false
    notice.value = '已保存修改后的新版本。查看制作范围后可继续制作，旧成片和旧报告保留。'
  }, '修改提案采纳失败，请核对是否已有更新')
  function changed() { dirty.value = true;quote.value = null;proposal.value = null }
  watch(form, () => { resetQuotes();proposal.value = null }, { deep: true })
  watch(budget, resetQuotes)
  watch(taskId, () => {
    generation++;loadVersion++;if (timer) clearTimeout(timer);refreshing = false;initialized = false;failures = 0;followLatest = true;lastFull = '';savedDocument = ''
    runtime.value = null;brief.value = null;board.value = null;boards.value = [];scenes.value = [];executions.value = [];preparation.value = null;preview.value = null;timing.value = null
    dirty.value = false;busy.value = false;error.value = '';notice.value = '';budget.value = 20;Object.assign(form, blank());resetQuotes();void refresh(true)
    proposal.value = null;instruction.value = ''
  }, { immediate: true, flush: 'post' })
  onBeforeUnmount(() => { disposed = true;generation++;if (timer) clearTimeout(timer) })
  return { form, available, loading, busy, error, notice, runtime, brief, board, boards, scenes, dirty, budget,
    locked, briefDirty, readyAssets, activeExecution, library, preparation, preparationQuote, preparationLimit,
    quote, executionLimit, executions, preview, timing, instruction, proposal, propose, acceptRevision, refresh, selectBoard, changed, save, estimatePrepare, confirmPrepare,
    saveScenes, estimate, confirm, cancel, cancelPreparation, showPreview, download, refreshPreview, checkTiming, acceptTiming, importSrt }
}
