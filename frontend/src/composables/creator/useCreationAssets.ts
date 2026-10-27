import { onBeforeUnmount, ref, watch, type Ref } from 'vue'
import * as api from '@/api/creationProduction'
import { putDraftVideoPart } from '@/api/media'
import { fileSha256 } from '@/utils/fileSha256'
import { errorText } from '@/utils/error'
import type { CreationAsset } from '@/types/creationProduction'

export function useCreationAssets(taskId: Ref<string>) {
  const assets = ref<CreationAsset[]>([]), busy = ref(false), progress = ref(''), error = ref('')
  let generation = 0, controller: AbortController | null = null
  const current = (token: number, task: string) => token === generation && task === taskId.value
  async function refresh() {
    const task = taskId.value, token = generation
    if (!task) return
    const result: CreationAsset[] = []
    for (let offset = 0; ; offset += 100) {
      const page = await api.listAssets(task, offset)
      if (!current(token, task)) return
      result.push(...page)
      if (page.length < 100) break
    }
    if (current(token, task)) assets.value = result
  }
  async function upload(file: File, kind: CreationAsset['kind']) {
    if (busy.value || !taskId.value) return
    const task = taskId.value, token = generation
    busy.value = true;error.value = '';controller = new AbortController()
    const check = () => { if (!current(token, task) || controller?.signal.aborted) throw new DOMException('已暂停','AbortError') }
    try {
      if (!file.size || file.size > 1024 * 1024 * 1024) throw new Error('请选择大于零且不超过 1 GiB 的素材文件')
      const sha = await fileSha256(file, ratio => { if (current(token, task)) progress.value = `校验文件 ${Math.round(ratio * 100)}%` }, controller.signal)
      check()
      await refresh();check()
      let asset = assets.value.find(a => a.kind === kind && a.sha256 === sha && a.fileSize === file.size && ['CREATING','CREATED','UPLOADING','COMPLETING','UPLOADED','PREPARING','READY'].includes(a.status))
      if (!asset) asset = await api.createAsset(task, kind, file, sha, api.creationKey())
      check()
      const deadline = Date.now() + 60000
      while (['CREATING','CREATED'].includes(asset.status)) {
        if (Date.now() >= deadline) throw new Error('上传会话还在准备，请核对素材处理服务。会话已保存，稍后重新选择同一文件可继续。')
        progress.value = '正在准备上传会话…'
        await new Promise(resolve => setTimeout(resolve, 800));check()
        asset = await api.readCreationAsset(task, asset.assetId);check()
      }
      if (['COMPLETING','UPLOADED','PREPARING','READY'].includes(asset.status)) {
        progress.value = asset.status === 'READY' ? '相同素材已准备完成，可直接使用' : '上传已完成，正在检查和准备素材'
        await refresh();return asset.assetId
      }
      if (asset.status !== 'UPLOADING') throw new Error(asset.failureMessage || '上传会话不可继续，请在素材列表中查看状态或重试')
      if (['CREATED','UPLOADING'].includes(asset.status)) {
        const completed = new Set(asset.parts.map(p => p.partNumber))
        for (let number = 1; number <= asset.totalParts; number++) {
          check()
          if (completed.has(number)) continue
          progress.value = `上传 ${file.name}，第 ${number} / ${asset.totalParts} 片`
          const signed = (await api.signAssetPart(task, asset.assetId, number))[0]
          check()
          if (!signed) throw new Error('未获得上传地址，请重试')
          const blob = file.slice((number - 1) * asset.partSize, Math.min(number * asset.partSize, file.size))
          const etag = await putDraftVideoPart(signed.url, blob, controller.signal)
          check()
          asset = await api.completeAssetPart(task, asset.assetId, number, etag, blob.size)
        }
      }
      check()
      await api.completeAsset(task, asset.assetId)
      check();progress.value = '上传完成，正在检查和准备素材';await refresh()
      return asset.assetId
    } catch (reason) {
      if (current(token, task)) error.value = reason instanceof DOMException && reason.name === 'AbortError' ? '上传已暂停。重新选择同一个文件可继续。' : errorText(reason, '素材上传失败，已保存完成的分片，可重新选择同一文件继续')
    } finally { if (current(token, task)) { busy.value = false;controller = null } }
  }
  function pause() { controller?.abort() }
  async function cancel(asset: CreationAsset) { await api.cancelAsset(taskId.value, asset.assetId);await refresh() }
  async function retry(asset: CreationAsset) { await api.retryAsset(taskId.value, asset.assetId);await refresh() }
  watch(taskId, () => { generation++;controller?.abort();controller = null;assets.value = [];busy.value = false;progress.value = '';error.value = '' }, { flush: 'sync' })
  onBeforeUnmount(() => { generation++;controller?.abort() })
  return { assets, busy, progress, error, refresh, upload, pause, cancel, retry }
}
