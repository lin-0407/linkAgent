<script setup lang="ts">
import type { CreatorContextTerm, CreatorContextTermType } from '@/types/creator'
import {
  contextTermSourceLabel,
  contextTermTypeLabel,
} from '@/composables/creator/creatorWorkspaceUtils'

defineProps<{
  open: boolean
  videoType: string
  options: Array<{ value: CreatorContextTermType; label: string }>
  terms: CreatorContextTerm[]
  canSave: boolean
  saving: boolean
  loading: boolean
}>()

const term = defineModel<string>('term', { required: true })
const termType = defineModel<CreatorContextTermType>('termType', { required: true })
const evidenceText = defineModel<string>('evidenceText', { required: true })

const emit = defineEmits<{
  close: []
  save: []
  reset: []
  feedback: [term: CreatorContextTerm, accepted: boolean]
  disable: [term: CreatorContextTerm]
}>()
</script>

<template>
  <Teleport to="body">
    <div v-if="open" class="creator-modal-backdrop" role="presentation">
      <section
        class="creator-result-modal creator-context-modal"
        role="dialog"
        aria-modal="true"
        aria-label="类型语境库"
      >
        <header class="creator-result-modal-head">
          <div>
            <p class="creator-kicker">类型语境库</p>
            <h3>{{ videoType === 'GLOBAL' ? '全局通用' : videoType }}</h3>
          </div>
          <button type="button" class="creator-ghost-button" @click="emit('close')">
            关闭
          </button>
        </header>

        <div class="creator-result-modal-body">
          <form class="creator-form-grid creator-context-form" @submit.prevent="emit('save')">
            <label>
              <span>词条</span>
              <input
                v-model="term"
                type="text"
                maxlength="128"
                placeholder="输入关键词、黑话、梗或慎用表达"
              />
            </label>
            <label>
              <span>类型</span>
              <select v-model="termType">
                <option v-for="option in options" :key="option.value" :value="option.value">
                  {{ option.label }}
                </option>
              </select>
            </label>
            <label class="span-full">
              <span>证据说明</span>
              <textarea
                v-model="evidenceText"
                maxlength="1000"
                placeholder="为什么这个词适合或不适合当前类型"
              ></textarea>
            </label>
            <div class="creator-action-row span-full">
              <button
                type="submit"
                class="creator-primary-button creator-mini-button"
                :disabled="!canSave"
              >
                {{ saving ? '保存中...' : '保存词条' }}
              </button>
              <button
                type="button"
                class="creator-ghost-button creator-mini-button"
                @click="emit('reset')"
              >
                清空
              </button>
            </div>
          </form>

          <div class="creator-context-list">
            <article
              v-for="contextTerm in terms"
              :key="contextTerm.termId"
              class="creator-result-block"
              :class="{ muted: !contextTerm.enabled }"
            >
              <span>{{ contextTermTypeLabel(contextTerm.termType) }}</span>
              <strong>{{ contextTerm.term }}</strong>
              <p v-if="contextTerm.evidenceText">{{ contextTerm.evidenceText }}</p>
              <small>
                {{ contextTermSourceLabel(contextTerm.sourceType) }} · 权重 {{ contextTerm.weight }} ·
                接受 {{ contextTerm.acceptCount }} · 拒绝 {{ contextTerm.rejectCount }}
              </small>
              <div class="creator-action-row">
                <button
                  type="button"
                  class="creator-ghost-button creator-mini-button"
                  @click="emit('feedback', contextTerm, true)"
                >
                  提高权重
                </button>
                <button
                  type="button"
                  class="creator-ghost-button creator-mini-button"
                  @click="emit('feedback', contextTerm, false)"
                >
                  降低权重
                </button>
                <button
                  v-if="contextTerm.enabled"
                  type="button"
                  class="creator-danger-action creator-mini-button"
                  @click="emit('disable', contextTerm)"
                >
                  禁用
                </button>
              </div>
            </article>
            <p v-if="!loading && terms.length === 0" class="creator-muted">
              当前类型还没有语境词条。
            </p>
          </div>
        </div>
      </section>
    </div>
  </Teleport>
</template>
