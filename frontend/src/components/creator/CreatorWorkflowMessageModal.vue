<script setup lang="ts">
import type { ComponentPublicInstance } from 'vue'
import {
  formatDate,
  hasText,
  materialLabel,
  previewWorkflowMessage,
  workflowContentTypeLabel,
  workflowRoleLabel,
} from '@/composables/creator/creatorWorkspaceUtils'
import type { CreatorMaterial, CreatorWorkflowMessage } from '@/types/creator'

const {
  open,
  statusText,
  sseText,
  hasSelectedTask,
  hasSelectedTaskMaterials,
  loading,
  messages,
  selectedMessage,
  selectedMaterial,
  draft,
  canSend,
  sending,
} = defineProps<{
  open: boolean
  statusText: string
  sseText: string
  hasSelectedTask: boolean
  hasSelectedTaskMaterials: boolean
  loading: boolean
  messages: CreatorWorkflowMessage[]
  selectedMessage: CreatorWorkflowMessage | null
  selectedMaterial: CreatorMaterial | null
  draft: string
  canSend: boolean
  sending: boolean
}>()

const emit = defineEmits<{
  close: []
  refresh: []
  send: []
  'update:selectedMessageId': [messageId: string]
  'update:draft': [draft: string]
  'message-list-ref': [element: HTMLDivElement | null]
}>()

function updateDraft(event: Event) {
  emit('update:draft', (event.target as HTMLTextAreaElement).value)
}

function setMessageListRef(element: Element | ComponentPublicInstance | null) {
  emit('message-list-ref', element instanceof HTMLDivElement ? element : null)
}
</script>

<template>
  <Teleport to="body">
    <div
      v-if="open"
      class="creator-modal-backdrop"
      role="presentation"
      @click.self="emit('close')"
    >
      <section
        class="creator-message-modal"
        role="dialog"
        aria-modal="true"
        aria-label="发布前优化消息流"
      >
        <header class="creator-result-modal-head">
          <div>
            <p class="creator-kicker">材料与消息</p>
            <h3>发布前优化消息流</h3>
          </div>
          <div class="creator-panel-actions">
            <span>{{ statusText }}</span>
            <span class="creator-sse-status" :class="{ active: sseText === '实时连接' }">
              {{ sseText }}
            </span>
            <button
              type="button"
              class="creator-secondary-action"
              :disabled="!hasSelectedTask || !hasSelectedTaskMaterials || loading"
              @click="emit('refresh')"
            >
              {{ loading ? '载入中' : '刷新消息' }}
            </button>
            <button type="button" class="creator-ghost-button" @click="emit('close')">
              关闭
            </button>
          </div>
        </header>

        <div class="creator-message-modal-body">
          <section class="creator-workflow-stream" aria-label="发布前优化消息列表">
            <div :ref="setMessageListRef" class="creator-workflow-message-list">
              <button
                v-for="message in messages"
                :key="message.messageId"
                type="button"
                class="creator-workflow-message"
                :class="[
                  `role-${message.role.toLowerCase()}`,
                  { active: message.messageId === selectedMessage?.messageId },
                ]"
                @click="emit('update:selectedMessageId', message.messageId)"
              >
                <small>
                  #{{ message.sequenceNo }} · {{ workflowRoleLabel(message.role) }} ·
                  {{ formatDate(message.createTime) }}
                </small>
                <strong>{{ previewWorkflowMessage(message.content) }}</strong>
                <span>{{ workflowContentTypeLabel(message.contentType) }}</span>
              </button>

              <p v-if="!loading && messages.length === 0" class="creator-muted">
                {{
                  hasSelectedTaskMaterials
                    ? '还没有工作流消息，选择任务后会自动装载材料。'
                    : '当前任务没有材料，无法装载发布前优化工作流。'
                }}
              </p>
            </div>
          </section>

          <section class="creator-workflow-detail" aria-label="工作流消息详情">
            <header class="creator-workflow-head">
              <h4>
                消息详情<span v-if="selectedMessage"
                  >-{{ workflowContentTypeLabel(selectedMessage.contentType) }}</span
                >
              </h4>
            </header>

            <article v-if="selectedMessage" class="creator-workflow-detail-body">
              <small>
                {{ workflowRoleLabel(selectedMessage.role) }} ·
                {{ formatDate(selectedMessage.createTime) }}
              </small>
              <strong v-if="selectedMaterial">
                {{ materialLabel(selectedMaterial.materialType) }}
              </strong>
              <p v-if="selectedMaterial">{{ selectedMessage.content }}</p>
              <pre>{{ selectedMaterial?.content || selectedMessage.content }}</pre>
            </article>

            <article v-else class="creator-empty-result">
              <strong>未选择消息</strong>
              <span>点击左侧消息可以查看完整材料或过程内容。</span>
            </article>
          </section>

          <form class="creator-workflow-composer" @submit.prevent="emit('send')">
            <textarea
              :value="draft"
              maxlength="2000"
              :disabled="!canSend || sending"
              placeholder="补充发布前优化要求，例如：标题更适合 Java 后端学习者，不要标题党"
              @input="updateDraft"
            ></textarea>
            <button
              type="submit"
              class="creator-primary-button"
              :disabled="!canSend || !hasText(draft) || sending"
            >
              {{ sending ? '发送中...' : '发送消息' }}
            </button>
          </form>
        </div>
      </section>
    </div>
  </Teleport>
</template>
