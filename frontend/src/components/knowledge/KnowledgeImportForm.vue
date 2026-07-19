<script setup lang="ts">
import { reactive, watch } from 'vue'

const props = defineProps<{
  importing: boolean
  clearToken: number
}>()

const emit = defineEmits<{
  import: [videoId: string, tier: string, category: string]
}>()

const TIER_OPTIONS = [
  { value: 'BENCHMARK', label: '标杆案例' },
  { value: 'COMPETITOR', label: '竞品案例' },
  { value: 'OWN_HISTORY', label: '自己历史' },
] as const

const form = reactive({
  bvInput: '',
  tier: 'BENCHMARK',
  category: '',
})

watch(() => props.clearToken, () => {
  form.bvInput = ''
})

function submitImport() {
  const bvInput = form.bvInput.trim()
  if (!bvInput || props.importing) {
    return
  }
  emit('import', bvInput, form.tier, form.category)
}
</script>

<template>
  <div class="knowledge-block knowledge-import-block">
    <div class="creator-section-head"><h3>添加参考案例</h3></div>
    <div class="knowledge-form">
      <label>
        <span>BV 号 / 视频链接</span>
        <input
          v-model="form.bvInput"
          type="text"
          placeholder="BV 号或视频链接"
          :disabled="importing"
          @keyup.enter="submitImport"
        />
      </label>
      <label>
        <span>案例层级</span>
        <select v-model="form.tier" :disabled="importing">
          <option v-for="option in TIER_OPTIONS" :key="option.value" :value="option.value">
            {{ option.label }}
          </option>
        </select>
      </label>
      <label>
        <span>分区（可选）</span>
        <input
          v-model="form.category"
          type="text"
          placeholder="分区"
          :disabled="importing"
        />
      </label>
      <button
        type="button"
        class="creator-primary-button knowledge-import-submit"
        :disabled="importing || !form.bvInput.trim()"
        @click="submitImport"
      >
        {{ importing ? '采集中…' : '采集并导入' }}
      </button>
    </div>
  </div>
</template>

<!--
  导入表单专用排版：导入属于低频补充动作，收紧留白后让检索和案例列表优先占据首屏。
  放 scoped 而非全局 theme.css：避免影响创作台其他模块，也避开全局主题文件的协作冲突。
-->
<style scoped>
.knowledge-block {
  display: grid;
  gap: var(--s3);
}

.knowledge-block > .creator-section-head {
  align-items: flex-start;
  padding-bottom: 0;
  border-bottom: 0;
}

.knowledge-import-block {
  gap: var(--s2);
}

.knowledge-form {
  display: grid;
  grid-template-columns: minmax(190px, 1.35fr) minmax(116px, 138px) minmax(130px, 0.65fr) auto;
  align-items: end;
  gap: var(--s2);
}

.knowledge-form label {
  display: grid;
  align-content: start;
  gap: var(--s2);
}

.knowledge-form label > span {
  color: var(--text);
  font-size: 13px;
  font-weight: var(--fw-semibold);
}

.knowledge-form input,
.knowledge-form select {
  width: 100%;
  min-height: 36px;
  padding: 0 var(--s3);
  color: var(--ink);
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--r-sm);
  outline: none;
}

.knowledge-form input:focus,
.knowledge-form select:focus {
  border-color: var(--accent);
  box-shadow: 0 0 0 4px var(--accent-ring);
}

.knowledge-import-submit {
  align-self: end;
  min-width: 112px;
  white-space: nowrap;
}

@media (max-width: 820px) {
  .knowledge-form {
    grid-template-columns: 1fr;
  }

  .knowledge-import-submit {
    width: 100%;
  }
}
</style>
