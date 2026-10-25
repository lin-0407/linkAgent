<script setup lang="ts">
import { reactive, ref } from 'vue'
import { get, post } from '@/api/http'
import { errorText } from '@/utils/error'

type Settings = { revision: number; restartRequired: boolean; encryptionAvailable: boolean; videoKeyConfigured: boolean; dashScopeKeyConfigured: boolean; loadError: string | null; videoProvider: string; videoBaseUrl: string; videoModel: string; videoDurationsSeconds: number[]; videoUsdPerSecond: number; voiceModel: string; voiceName: string; voiceUsdPerMillionCharacters: number }
const emit = defineEmits<{ changed: [] }>()
const state = ref<Settings | null>(null), busy = ref(false), error = ref(''), notice = ref(''), durations = ref('')
const form = reactive({ videoProvider:'compatible',videoBaseUrl:'',videoModel:'',videoApiKey:'',videoUsdPerSecond:0.1,voiceModel:'',voiceName:'',voiceUsdPerMillionCharacters:28,dashScopeApiKey:'' })
async function load(event: Event) {
  if (!(event.target as HTMLDetailsElement).open || state.value || busy.value) return
  busy.value = true;error.value = ''
  try { const value = await get<Settings>('/settings/creation-providers');state.value = value
    Object.assign(form, { videoProvider:value.videoProvider,videoBaseUrl:value.videoBaseUrl,videoModel:value.videoModel,videoUsdPerSecond:Number(value.videoUsdPerSecond),voiceModel:value.voiceModel,voiceName:value.voiceName,voiceUsdPerMillionCharacters:Number(value.voiceUsdPerMillionCharacters) });durations.value = value.videoDurationsSeconds.join(', ')
  } catch(reason) { error.value = errorText(reason, '制作配置读取失败') } finally { busy.value = false }
}
async function save() {
  if (!state.value || busy.value) return
  const values = durations.value.split(/[,，\s]+/).filter(Boolean).map(Number)
  if (!values.length || values.length > 12 || values.some(v => !Number.isInteger(v) || v < 1 || v > 60)) { error.value = '支持时长请填写 1 到 60 秒的整数，以逗号分隔。';return }
  if (!window.confirm('保存这些制作配置后，需要重启后端才能继续发起制作或试映。确定保存吗？')) return
  busy.value = true;error.value = '';notice.value = ''
  try { state.value = await post<Settings>('/settings/creation-providers', { ...form, expectedRevision:state.value.revision, videoDurationsSeconds:[...new Set(values)].sort((a,b)=>a-b) })
    form.videoApiKey = '';form.dashScopeApiKey = '';notice.value = '已保存。请重启后端让配置生效，再打开设置中的配音或 AI 视频开关。';emit('changed')
  } catch(reason) { error.value = errorText(reason, '制作配置保存失败，请刷新核对配置后再试') } finally { busy.value = false }
}
</script>

<template>
  <details class="provider-settings" @toggle="load"><summary>制作模型与密钥 · 部署者设置</summary>
    <p>保存不会调用供应商或测试付费能力；重启后端后生效。密钥留空会保留原配置，保存的密钥使用项目现有加密方式。</p>
    <p v-if="error" role="alert" class="provider-error">{{ error }}</p><p v-if="notice" role="status">{{ notice }}</p>
    <p v-if="busy && !state">正在读取配置…</p>
    <template v-if="state"><p v-if="state.restartRequired" role="status">配置尚未生效，需要重启后端。{{ state.loadError }}</p><p v-if="!state.encryptionAvailable">后端尚未配置密钥加密项 LINKAGENT_AES_KEY；现在可保存模型参数，新增密钥须先完成该部署配置。</p>
      <form @submit.prevent="save"><fieldset :disabled="busy"><legend>AI 视频</legend>
        <label>供应商标识<input v-model.trim="form.videoProvider" required pattern="[A-Za-z0-9._-]{1,80}" /></label>
        <label>服务地址<input v-model.trim="form.videoBaseUrl" type="url" maxlength="512" placeholder="服务的基础地址" /></label>
        <label>模型<input v-model.trim="form.videoModel" maxlength="128" /></label>
        <label>API 密钥{{ state.videoKeyConfigured ? '（已配置）' : '（未配置）' }}<input v-model="form.videoApiKey" type="password" autocomplete="new-password" maxlength="512" :disabled="!state.encryptionAvailable" placeholder="留空保留" /></label>
        <label>支持时长（秒）<input v-model="durations" required placeholder="5, 10" /></label>
        <label>价格上界（美元 / 秒）<input v-model.number="form.videoUsdPerSecond" type="number" required min="0.00000001" step="0.00000001" /></label>
      </fieldset><fieldset :disabled="busy"><legend>配音与转写</legend>
        <label>配音模型<input v-model.trim="form.voiceModel" required maxlength="128" /></label><label>音色<input v-model.trim="form.voiceName" required maxlength="128" /></label>
        <label>价格上界（美元 / 百万字）<input v-model.number="form.voiceUsdPerMillionCharacters" type="number" required min="0.00000001" step="0.00000001" /></label>
        <label>DashScope 密钥{{ state.dashScopeKeyConfigured ? '（已配置）' : '（未配置）' }}<input v-model="form.dashScopeApiKey" type="password" autocomplete="new-password" maxlength="512" :disabled="!state.encryptionAvailable" placeholder="留空保留，配音、转写和视频体检共用" /></label>
      </fieldset><p>价格是预算记账估值，真实费用以供应商账单为准。视频服务须支持当前异步任务适配协议，填写地址本身不能保证模型兼容。</p><button type="submit" :disabled="busy">保存配置（重启后生效）</button></form>
    </template>
  </details>
</template>

<style scoped>
.provider-settings{border:1px solid var(--border);border-radius:12px;padding:14px 18px;margin:14px 0;color:var(--ink)}summary{cursor:pointer;font-weight:600}.provider-settings p{font-size:13px;line-height:1.7;color:var(--muted)}fieldset{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:12px;border:1px solid var(--border);border-radius:8px;margin:12px 0;padding:14px}legend{padding:0 6px;font-weight:600}label{display:flex;flex-direction:column;gap:6px;font-size:13px}input{box-sizing:border-box;width:100%;min-width:0;border:1px solid var(--border);border-radius:6px;padding:9px;background:var(--surface,#fff);color:inherit}button{cursor:pointer;padding:9px 14px;border:1px solid var(--border);border-radius:7px;background:var(--surface,#fff);color:inherit}.provider-error{color:var(--danger-color,#b42318)!important}@media(max-width:680px){fieldset{grid-template-columns:1fr}}
</style>
