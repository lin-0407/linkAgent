<script setup lang="ts">
import { computed, ref } from 'vue'
import SuggestionRejectPanel from './SuggestionRejectPanel.vue'
import type { CreatorRejectReason } from '@/types/creator'

/**
 * 可复用的建议卡片。
 *
 * 设计目标：把"标题建议/标签建议/简介建议"等不同类型的结果，
 * 统一成同一种"卡片 + 元信息 + 操作区"的展示形态，告别原来的纯文本列表。
 *
 * 卡片只做三件事：展示建议内容、展示元信息、把用户的操作意图通过事件抛给父组件。
 * 数据解析（把 JSON 字符串拆成 title/reason 等字段）由父组件完成，
 * 这样卡片不依赖具体的数据结构，能同时承载标题、标签、简介等多种建议。
 *
 * 反馈（采纳/复制/不太好）的事件由父组件统一调用 useCreatorFeedbackEvent 上报后端，
 * 卡片本身不直接调接口，保持展示与数据解耦。
 */

/** 卡片支持的类型，决定强调色与角标文案 */
type SuggestionType = 'title' | 'tag' | 'description' | 'generic'

/** 一条建议需要展示的全部已解析信息，由父组件准备好后传入 */
interface SuggestionItem {
  /** 建议正文，如标题原文、标签词 */
  content: string
  /** 观众心理（标题建议特有） */
  viewerPsychology?: string
  /** 点击理由（标题建议特有） */
  clickReason?: string
  /** 信任风险（标题建议特有） */
  trustRisk?: string
  /** 适用场景 */
  bestScenario?: string
  /** 推荐理由 / 通用说明 */
  reason?: string
  /** 风险提示 */
  risk?: string
}

const props = withDefaults(
  defineProps<{
    /** 建议类型，用于切换强调色和角标 */
    type?: SuggestionType
    /** 已解析的建议内容 */
    item: SuggestionItem
    /** 在结果列表中的序号，从 1 开始；rank===1 时显示"推荐"角标 */
    rank?: number
    /** 是否展示反馈操作区（采纳/不太好），默认展示 */
    feedbackEnabled?: boolean
    /** 该卡片是否处于上报中，用于禁用按钮，防止重复点击 */
    reporting?: boolean
    /** 该卡片是否已被用户采纳（采纳后改变视觉态） */
    accepted?: boolean
  }>(),
  {
    type: 'generic',
    rank: 0,
    feedbackEnabled: true,
    reporting: false,
    accepted: false,
  },
)

const emit = defineEmits<{
  /** 采纳：父组件应上报 ACCEPTED 事件 */
  accept: [item: SuggestionItem]
  /** 复制：父组件负责把 content 写入剪贴板 */
  copy: [item: SuggestionItem]
  /** 拒绝：父组件应上报 REJECTED 事件，reason/reasonText 来自拒绝面板 */
  reject: [item: SuggestionItem, reason: CreatorRejectReason, reasonText: string]
}>()

// "不太好"面板的展开状态
const showRejectPanel = ref(false)
// 复制成功的瞬时提示
const copied = ref(false)
let copiedTimer: ReturnType<typeof setTimeout> | undefined

// 是否展示"推荐"角标：仅标题类建议且排名第一时
const showRecommendBadge = computed(
  () => props.type === 'title' && props.rank === 1,
)

// 拒绝事件类型由父组件根据 type 决定，这里只透传原因
function onRejectSubmit(reason: CreatorRejectReason, reasonText: string) {
  emit('reject', props.item, reason, reasonText)
  showRejectPanel.value = false
}

function onCopy() {
  emit('copy', props.item)
  // 复制成功的视觉反馈，2 秒后恢复
  copied.value = true
  if (copiedTimer) clearTimeout(copiedTimer)
  copiedTimer = setTimeout(() => {
    copied.value = false
  }, 2000)
}
</script>

<template>
  <article class="suggestion-card" :class="[`type-${type}`, { accepted }]">
    <!-- 角标：推荐 / 已采纳 -->
    <div class="card-badges">
      <span v-if="showRecommendBadge" class="badge recommend">推荐</span>
      <span v-if="accepted" class="badge accepted">已采纳</span>
    </div>

    <!-- 建议正文 -->
    <p class="card-content">{{ item.content }}</p>

    <!-- 元信息区：按需展示，没有就不渲染，避免空行 -->
    <div v-if="item.viewerPsychology || item.clickReason || item.trustRisk || item.bestScenario || item.reason || item.risk" class="card-meta">
      <p v-if="item.viewerPsychology"><span>观众心理</span>{{ item.viewerPsychology }}</p>
      <p v-if="item.clickReason"><span>点击理由</span>{{ item.clickReason }}</p>
      <p v-if="item.trustRisk"><span>信任风险</span>{{ item.trustRisk }}</p>
      <p v-if="item.bestScenario"><span>适用场景</span>{{ item.bestScenario }}</p>
      <p v-if="item.reason"><span>理由</span>{{ item.reason }}</p>
      <p v-if="item.risk" class="meta-risk"><span>风险</span>{{ item.risk }}</p>
    </div>

    <!-- 操作区：采纳 / 复制 / 不太好 -->
    <div v-if="feedbackEnabled" class="card-actions">
      <button
        type="button"
        class="creator-primary-button creator-mini-button"
        :disabled="reporting || accepted"
        @click="emit('accept', item)"
      >
        {{ accepted ? '已采纳' : '采纳' }}
      </button>
      <button
        type="button"
        class="creator-ghost-button creator-mini-button"
        :disabled="reporting"
        @click="onCopy"
      >
        {{ copied ? '已复制' : '复制' }}
      </button>
      <button
        v-if="!accepted"
        type="button"
        class="creator-ghost-button creator-mini-button"
        :disabled="reporting"
        @click="showRejectPanel = !showRejectPanel"
      >
        {{ showRejectPanel ? '收起' : '不太好 ▾' }}
      </button>

      <!-- 二级操作插槽：如"保存为标题套路"，由父组件按需塞入 -->
      <slot name="secondary-actions" />
    </div>

    <!-- 拒绝原因面板：点"不太好"展开 -->
    <SuggestionRejectPanel
      v-if="showRejectPanel && !accepted"
      @submit="onRejectSubmit"
      @cancel="showRejectPanel = false"
    />
  </article>
</template>

<style scoped>
.suggestion-card {
  position: relative;
  display: grid;
  gap: var(--s2);
  padding: var(--s3);
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--r);
  transition: border-color 0.15s, box-shadow 0.15s;
}

.suggestion-card:hover {
  border-color: var(--accent-ring);
  box-shadow: var(--sh-md);
}

/* 推荐卡片用更醒目的边框 */
.type-title {
  border-color: var(--accent-ring);
}

/* 已采纳态：降低饱和度，让用户一眼分清已处理 */
.suggestion-card.accepted {
  background: var(--surface-sub);
  border-style: dashed;
}

.card-badges {
  display: flex;
  flex-wrap: wrap;
  gap: var(--s1);
}

.badge {
  display: inline-flex;
  align-items: center;
  padding: 2px var(--s2);
  border-radius: var(--r-pill);
  font-size: 11px;
  font-weight: var(--fw-semibold);
  line-height: 1.6;
}

.badge.recommend {
  color: var(--accent-strong, var(--accent));
  background: var(--accent-tint);
}

.badge.accepted {
  color: #fff;
  background: var(--muted);
}

.card-content {
  margin: 0;
  font-size: 14px;
  font-weight: var(--fw-semibold);
  line-height: 1.52;
  color: var(--ink);
  word-break: break-word;
}

/* 标签类建议更紧凑，像 chip */
.type-tag .card-content {
  font-size: 13px;
  font-weight: var(--fw-medium);
}

.card-meta {
  display: grid;
  gap: var(--s1);
  padding-top: var(--s2);
  border-top: 1px dashed var(--border);
}

.card-meta p {
  margin: 0;
  font-size: 12px;
  line-height: 1.5;
  color: var(--muted);
}

.card-meta p span {
  display: inline-block;
  min-width: 56px;
  margin-right: var(--s2);
  color: var(--ink);
  font-weight: var(--fw-medium);
}

.meta-risk span {
  color: var(--danger);
}

.card-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--s2);
}
</style>
