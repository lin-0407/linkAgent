<script setup lang="ts">
import { onMounted, ref } from 'vue'
import {
  getReferenceVideoChunkIndexStatus,
  getReferenceVideoHybridIndexStatus,
  getReferenceVideoIndexStatus,
  getReferenceVideoItemHybridIndexStatus,
  getReferenceVideoItemIndexStatus,
  rebuildReferenceVideoChunkIndex,
  rebuildReferenceVideoHybridIndex,
  rebuildReferenceVideoIndex,
  rebuildReferenceVideoItemHybridIndex,
  rebuildReferenceVideoItemIndex,
} from '@/api/knowledge'
import type { ReferenceVideoIndexResult, ReferenceVideoIndexStatus } from '@/types/knowledge'

// 向量索引状态与重建。RAG 关闭时 indexStatus.vectorStoreReady=false，重建按钮禁用。
const indexStatus = ref<ReferenceVideoIndexStatus | null>(null)
const indexLoading = ref(false)
const indexStatusError = ref('')
const indexing = ref(false)
const indexResult = ref<ReferenceVideoIndexResult | null>(null)
const indexError = ref('')

// 主题中块索引状态与重建。topic-search 先查中块集合，父表已索引不代表主题检索可用。
const chunkIndexStatus = ref<ReferenceVideoIndexStatus | null>(null)
const chunkIndexLoading = ref(false)
const chunkIndexStatusError = ref('')
const chunkIndexing = ref(false)
const chunkIndexResult = ref<ReferenceVideoIndexResult | null>(null)
const chunkIndexError = ref('')

// 子条目向量索引状态与重建。子集合未就绪时重建按钮禁用。
const itemIndexStatus = ref<ReferenceVideoIndexStatus | null>(null)
const itemIndexLoading = ref(false)
const itemIndexStatusError = ref('')
const itemIndexing = ref(false)
const itemIndexResult = ref<ReferenceVideoIndexResult | null>(null)
const itemIndexError = ref('')

// 原生 hybrid 索引状态与重灌。vectorStoreReady=hybrid 库是否就绪。
const hybridIndexStatus = ref<ReferenceVideoIndexStatus | null>(null)
const hybridIndexLoading = ref(false)
const hybridIndexStatusError = ref('')
const hybridIndexing = ref(false)
const hybridIndexResult = ref<ReferenceVideoIndexResult | null>(null)
const hybridIndexError = ref('')

// 子条目原生 hybrid 索引状态与重灌。
const childHybridIndexStatus = ref<ReferenceVideoIndexStatus | null>(null)
const childHybridIndexLoading = ref(false)
const childHybridIndexStatusError = ref('')
const childHybridIndexing = ref(false)
const childHybridIndexResult = ref<ReferenceVideoIndexResult | null>(null)
const childHybridIndexError = ref('')

async function loadIndexStatus() {
  indexLoading.value = true
  indexStatusError.value = ''
  try {
    indexStatus.value = await getReferenceVideoIndexStatus()
  } catch (error) {
    indexStatusError.value = error instanceof Error ? error.message : String(error)
  } finally {
    indexLoading.value = false
  }
}

async function rebuildIndex() {
  if (indexing.value) {
    return
  }
  indexing.value = true
  indexError.value = ''
  indexResult.value = null
  try {
    indexResult.value = await rebuildReferenceVideoIndex()
    await loadIndexStatus()
  } catch (error) {
    indexError.value = error instanceof Error ? error.message : String(error)
  } finally {
    indexing.value = false
  }
}

async function loadChunkIndexStatus() {
  chunkIndexLoading.value = true
  chunkIndexStatusError.value = ''
  try {
    chunkIndexStatus.value = await getReferenceVideoChunkIndexStatus()
  } catch (error) {
    chunkIndexStatusError.value = error instanceof Error ? error.message : String(error)
  } finally {
    chunkIndexLoading.value = false
  }
}

async function rebuildChunkIndex() {
  if (chunkIndexing.value) {
    return
  }
  chunkIndexing.value = true
  chunkIndexError.value = ''
  chunkIndexResult.value = null
  try {
    chunkIndexResult.value = await rebuildReferenceVideoChunkIndex()
    await loadChunkIndexStatus()
  } catch (error) {
    chunkIndexError.value = error instanceof Error ? error.message : String(error)
  } finally {
    chunkIndexing.value = false
  }
}

async function loadItemIndexStatus() {
  itemIndexLoading.value = true
  itemIndexStatusError.value = ''
  try {
    itemIndexStatus.value = await getReferenceVideoItemIndexStatus()
  } catch (error) {
    itemIndexStatusError.value = error instanceof Error ? error.message : String(error)
  } finally {
    itemIndexLoading.value = false
  }
}

async function rebuildItemIndex() {
  if (itemIndexing.value) {
    return
  }
  itemIndexing.value = true
  itemIndexError.value = ''
  itemIndexResult.value = null
  try {
    itemIndexResult.value = await rebuildReferenceVideoItemIndex()
    await loadItemIndexStatus()
  } catch (error) {
    itemIndexError.value = error instanceof Error ? error.message : String(error)
  } finally {
    itemIndexing.value = false
  }
}

async function loadHybridIndexStatus() {
  hybridIndexLoading.value = true
  hybridIndexStatusError.value = ''
  try {
    hybridIndexStatus.value = await getReferenceVideoHybridIndexStatus()
  } catch (error) {
    hybridIndexStatusError.value = error instanceof Error ? error.message : String(error)
  } finally {
    hybridIndexLoading.value = false
  }
}

async function rebuildHybridIndex() {
  if (hybridIndexing.value) {
    return
  }
  hybridIndexing.value = true
  hybridIndexError.value = ''
  hybridIndexResult.value = null
  try {
    // 整库重灌会 drop 旧 hybrid 集合再从 MySQL 重建，所以必须保留明确文案提醒用户这是运维动作。
    hybridIndexResult.value = await rebuildReferenceVideoHybridIndex()
    await loadHybridIndexStatus()
  } catch (error) {
    hybridIndexError.value = error instanceof Error ? error.message : String(error)
  } finally {
    hybridIndexing.value = false
  }
}

async function loadChildHybridIndexStatus() {
  childHybridIndexLoading.value = true
  childHybridIndexStatusError.value = ''
  try {
    childHybridIndexStatus.value = await getReferenceVideoItemHybridIndexStatus()
  } catch (error) {
    childHybridIndexStatusError.value = error instanceof Error ? error.message : String(error)
  } finally {
    childHybridIndexLoading.value = false
  }
}

async function rebuildChildHybridIndex() {
  if (childHybridIndexing.value) {
    return
  }
  childHybridIndexing.value = true
  childHybridIndexError.value = ''
  childHybridIndexResult.value = null
  try {
    childHybridIndexResult.value = await rebuildReferenceVideoItemHybridIndex()
    await loadChildHybridIndexStatus()
  } catch (error) {
    childHybridIndexError.value = error instanceof Error ? error.message : String(error)
  } finally {
    childHybridIndexing.value = false
  }
}

function retrievalModeLabel(mode: string) {
  return mode === 'VECTOR' ? '向量检索' : 'SQL 检索'
}

onMounted(() => {
  void loadIndexStatus()
  void loadChunkIndexStatus()
  void loadItemIndexStatus()
  void loadHybridIndexStatus()
  void loadChildHybridIndexStatus()
})
</script>

<template>
  <div class="knowledge-index-panels">
    <section class="creator-section settings-index-section">
      <div class="creator-section-head">
        <h3>向量索引</h3>
        <div class="knowledge-toolbar">
          <button type="button" class="creator-secondary-action" :disabled="indexLoading" @click="loadIndexStatus">
            {{ indexLoading ? '刷新中…' : '刷新状态' }}
          </button>
          <button
            type="button"
            class="creator-primary-button"
            :disabled="indexing || !indexStatus?.vectorStoreReady"
            @click="rebuildIndex"
          >
            {{ indexing ? '索引中…' : '重建索引' }}
          </button>
        </div>
      </div>
      <p class="creator-inline-note">
        把案例卡片写入向量库供语义检索使用。需开启 knowledge.rag 并配置 Embedding 与 Milvus；关闭时显示降级状态、重建按钮不可用。
      </p>

      <div v-if="indexStatusError" class="creator-alert error-alert">
        <strong>状态加载失败</strong>
        <span>{{ indexStatusError }}</span>
      </div>
      <div v-else-if="indexStatus" class="creator-chip-list">
        <b>{{ indexStatus.ragEnabled ? 'RAG 已启用' : 'RAG 关闭' }}</b>
        <b>{{ indexStatus.vectorStoreReady ? '向量库就绪' : '向量库未就绪' }}</b>
        <b>检索模式 {{ retrievalModeLabel(indexStatus.retrievalMode) }}</b>
        <b>已索引 {{ indexStatus.indexedCount }}</b>
        <b>待索引 {{ indexStatus.pendingCount }}</b>
        <b v-if="indexStatus.failedCount > 0">失败 {{ indexStatus.failedCount }}</b>
        <b>共 {{ indexStatus.totalCount }}</b>
      </div>

      <div v-if="indexResult" class="creator-alert success-alert">
        <strong>重建完成</strong>
        <span>
          本次索引 {{ indexResult.indexedCount }} 条<template v-if="indexResult.failedCount > 0">，失败 {{ indexResult.failedCount }} 条</template>。
        </span>
      </div>
      <div v-if="indexError" class="creator-alert error-alert">
        <strong>重建失败</strong>
        <span>{{ indexError }}</span>
      </div>
      <ul v-if="indexResult && indexResult.warnings.length" class="knowledge-warnings">
        <li v-for="(warning, idx) in indexResult.warnings" :key="idx">{{ warning }}</li>
      </ul>
    </section>

    <section class="creator-section settings-index-section">
      <div class="creator-section-head">
        <h3>主题中块索引</h3>
        <div class="knowledge-toolbar">
          <button type="button" class="creator-secondary-action" :disabled="chunkIndexLoading" @click="loadChunkIndexStatus">
            {{ chunkIndexLoading ? '刷新中…' : '刷新状态' }}
          </button>
          <button
            type="button"
            class="creator-primary-button"
            :disabled="chunkIndexing || !chunkIndexStatus?.vectorStoreReady"
            @click="rebuildChunkIndex"
          >
            {{ chunkIndexing ? '索引中…' : '重建主题中块索引' }}
          </button>
        </div>
      </div>
      <p class="creator-inline-note">
        把标题包装、内容定位和观众反馈主题写入中块集合。案例检索的主题优先模式先查这一层，父表已索引不等于中块已索引。
      </p>

      <div v-if="chunkIndexStatusError" class="creator-alert error-alert">
        <strong>状态加载失败</strong>
        <span>{{ chunkIndexStatusError }}</span>
      </div>
      <div v-else-if="chunkIndexStatus" class="creator-chip-list">
        <b>{{ chunkIndexStatus.ragEnabled ? 'RAG 已启用' : 'RAG 关闭' }}</b>
        <b>{{ chunkIndexStatus.vectorStoreReady ? '中块向量库就绪' : '中块向量库未就绪' }}</b>
        <b>检索模式 {{ retrievalModeLabel(chunkIndexStatus.retrievalMode) }}</b>
        <b>已索引 {{ chunkIndexStatus.indexedCount }}</b>
        <b>待索引 {{ chunkIndexStatus.pendingCount }}</b>
        <b v-if="chunkIndexStatus.failedCount > 0">失败 {{ chunkIndexStatus.failedCount }}</b>
        <b>共 {{ chunkIndexStatus.totalCount }}</b>
      </div>

      <div v-if="chunkIndexResult" class="creator-alert success-alert">
        <strong>重建完成</strong>
        <span>
          本次索引 {{ chunkIndexResult.indexedCount }} 条主题中块<template v-if="chunkIndexResult.failedCount > 0">，失败 {{ chunkIndexResult.failedCount }} 条</template>。
        </span>
      </div>
      <div v-if="chunkIndexError" class="creator-alert error-alert">
        <strong>重建失败</strong>
        <span>{{ chunkIndexError }}</span>
      </div>
      <ul v-if="chunkIndexResult && chunkIndexResult.warnings.length" class="knowledge-warnings">
        <li v-for="(warning, idx) in chunkIndexResult.warnings" :key="idx">{{ warning }}</li>
      </ul>
    </section>

    <section class="creator-section settings-index-section">
      <div class="creator-section-head">
        <h3>子条目索引</h3>
        <div class="knowledge-toolbar">
          <button type="button" class="creator-secondary-action" :disabled="itemIndexLoading" @click="loadItemIndexStatus">
            {{ itemIndexLoading ? '刷新中…' : '刷新状态' }}
          </button>
          <button
            type="button"
            class="creator-primary-button"
            :disabled="itemIndexing || !itemIndexStatus?.vectorStoreReady"
            @click="rebuildItemIndex"
          >
            {{ itemIndexing ? '索引中…' : '重建子条目索引' }}
          </button>
        </div>
      </div>
      <p class="creator-inline-note">
        把优质评论 / 弹幕原文写入独立子集合，作为父子召回的小颗粒召回端。子集合未就绪只影响子召回，不影响案例检索。
      </p>

      <div v-if="itemIndexStatusError" class="creator-alert error-alert">
        <strong>状态加载失败</strong>
        <span>{{ itemIndexStatusError }}</span>
      </div>
      <div v-else-if="itemIndexStatus" class="creator-chip-list">
        <b>{{ itemIndexStatus.ragEnabled ? 'RAG 已启用' : 'RAG 关闭' }}</b>
        <b>{{ itemIndexStatus.vectorStoreReady ? '子向量库就绪' : '子向量库未就绪' }}</b>
        <b>已索引 {{ itemIndexStatus.indexedCount }}</b>
        <b>待索引 {{ itemIndexStatus.pendingCount }}</b>
        <b v-if="itemIndexStatus.failedCount > 0">失败 {{ itemIndexStatus.failedCount }}</b>
        <b>共 {{ itemIndexStatus.totalCount }}</b>
      </div>

      <div v-if="itemIndexResult" class="creator-alert success-alert">
        <strong>重建完成</strong>
        <span>
          本次索引 {{ itemIndexResult.indexedCount }} 条子条目<template v-if="itemIndexResult.failedCount > 0">，失败 {{ itemIndexResult.failedCount }} 条</template>。
        </span>
      </div>
      <div v-if="itemIndexError" class="creator-alert error-alert">
        <strong>重建失败</strong>
        <span>{{ itemIndexError }}</span>
      </div>
      <ul v-if="itemIndexResult && itemIndexResult.warnings.length" class="knowledge-warnings">
        <li v-for="(warning, idx) in itemIndexResult.warnings" :key="idx">{{ warning }}</li>
      </ul>
    </section>

    <section class="creator-section settings-index-section">
      <div class="creator-section-head">
        <h3>原生 hybrid 索引</h3>
        <div class="knowledge-toolbar">
          <button type="button" class="creator-secondary-action" :disabled="hybridIndexLoading" @click="loadHybridIndexStatus">
            {{ hybridIndexLoading ? '刷新中…' : '刷新状态' }}
          </button>
          <button
            type="button"
            class="creator-primary-button"
            :disabled="hybridIndexing || !hybridIndexStatus?.vectorStoreReady"
            @click="rebuildHybridIndex"
          >
            {{ hybridIndexing ? '重灌中…' : '重建 hybrid 索引（重灌）' }}
          </button>
        </div>
      </div>
      <p class="creator-inline-note">
        把案例卡片灌入自建 schema 的 hybrid 集合，供 dense 语义 + BM25 关键词混合检索用。整库重灌会 drop 后从 MySQL 重建。
      </p>

      <div v-if="hybridIndexStatusError" class="creator-alert error-alert">
        <strong>状态加载失败</strong>
        <span>{{ hybridIndexStatusError }}</span>
      </div>
      <div v-else-if="hybridIndexStatus" class="creator-chip-list">
        <b>{{ hybridIndexStatus.ragEnabled ? 'RAG 已启用' : 'RAG 关闭' }}</b>
        <b>{{ hybridIndexStatus.vectorStoreReady ? 'hybrid 库就绪' : 'hybrid 库未就绪' }}</b>
        <b>检索模式 {{ hybridIndexStatus.retrievalMode }}</b>
        <b>可重灌案例 {{ hybridIndexStatus.totalCount }}</b>
      </div>

      <div v-if="hybridIndexResult" class="creator-alert success-alert">
        <strong>重灌完成</strong>
        <span>
          本次灌入 {{ hybridIndexResult.indexedCount }} 条案例<template v-if="hybridIndexResult.failedCount > 0">，失败 {{ hybridIndexResult.failedCount }} 条</template>。
        </span>
      </div>
      <div v-if="hybridIndexError" class="creator-alert error-alert">
        <strong>重灌失败</strong>
        <span>{{ hybridIndexError }}</span>
      </div>
      <ul v-if="hybridIndexResult && hybridIndexResult.warnings.length" class="knowledge-warnings">
        <li v-for="(warning, idx) in hybridIndexResult.warnings" :key="idx">{{ warning }}</li>
      </ul>
    </section>

    <section class="creator-section settings-index-section">
      <div class="creator-section-head">
        <h3>子条目 hybrid 索引</h3>
        <div class="knowledge-toolbar">
          <button type="button" class="creator-secondary-action" :disabled="childHybridIndexLoading" @click="loadChildHybridIndexStatus">
            {{ childHybridIndexLoading ? '刷新中…' : '刷新状态' }}
          </button>
          <button
            type="button"
            class="creator-primary-button"
            :disabled="childHybridIndexing || !childHybridIndexStatus?.vectorStoreReady"
            @click="rebuildChildHybridIndex"
          >
            {{ childHybridIndexing ? '重灌中…' : '重建子 hybrid 索引（重灌）' }}
          </button>
        </div>
      </div>
      <p class="creator-inline-note">
        把优质评论 / 弹幕原文灌入子 hybrid 集合，hybrid 开启时 small-to-big 子召回走它。整库重灌会 drop 后从 MySQL 重建。
      </p>

      <div v-if="childHybridIndexStatusError" class="creator-alert error-alert">
        <strong>状态加载失败</strong>
        <span>{{ childHybridIndexStatusError }}</span>
      </div>
      <div v-else-if="childHybridIndexStatus" class="creator-chip-list">
        <b>{{ childHybridIndexStatus.ragEnabled ? 'RAG 已启用' : 'RAG 关闭' }}</b>
        <b>{{ childHybridIndexStatus.vectorStoreReady ? '子 hybrid 库就绪' : '子 hybrid 库未就绪' }}</b>
        <b>检索模式 {{ childHybridIndexStatus.retrievalMode }}</b>
        <b>可重灌子条目 {{ childHybridIndexStatus.totalCount }}</b>
      </div>

      <div v-if="childHybridIndexResult" class="creator-alert success-alert">
        <strong>重灌完成</strong>
        <span>
          本次灌入 {{ childHybridIndexResult.indexedCount }} 条子条目<template v-if="childHybridIndexResult.failedCount > 0">，失败 {{ childHybridIndexResult.failedCount }} 条</template>。
        </span>
      </div>
      <div v-if="childHybridIndexError" class="creator-alert error-alert">
        <strong>重灌失败</strong>
        <span>{{ childHybridIndexError }}</span>
      </div>
      <ul v-if="childHybridIndexResult && childHybridIndexResult.warnings.length" class="knowledge-warnings">
        <li v-for="(warning, idx) in childHybridIndexResult.warnings" :key="idx">{{ warning }}</li>
      </ul>
    </section>
  </div>
</template>

<style scoped>
.knowledge-index-panels {
  display: grid;
  gap: var(--s3);
}

.settings-index-section {
  max-width: none;
  margin: 0;
}

.knowledge-toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: var(--s2);
  align-items: center;
  padding-bottom: var(--s3);
  border-bottom: 1px solid var(--border);
}

.knowledge-warnings {
  display: grid;
  gap: 4px;
  margin: 0;
  padding-left: 18px;
  color: var(--muted);
  font-size: 12px;
  line-height: 1.5;
}
</style>
