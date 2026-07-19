<script setup lang="ts">
import type { CreatorTaskSummary } from '@/types/creator'

defineProps<{
  pendingTask: CreatorTaskSummary | null
  deleting: boolean
}>()

const emit = defineEmits<{
  cancel: []
  confirm: []
}>()
</script>

<template>
  <Teleport to="body">
    <Transition name="creator-modal">
      <div
        v-if="pendingTask"
        class="creator-modal-backdrop creator-delete-modal-backdrop"
        @click.self="!deleting && emit('cancel')"
      >
        <section
          class="creator-delete-confirm-modal"
          role="alertdialog"
          aria-modal="true"
          aria-labelledby="creator-delete-confirm-title"
          aria-describedby="creator-delete-confirm-desc"
        >
          <header>
            <span>删除项目</span>
            <h3 id="creator-delete-confirm-title">删除「{{ pendingTask.taskName }}」？</h3>
          </header>
          <p id="creator-delete-confirm-desc">
            项目会从列表隐藏，历史分析产物保留在后端。这个操作完成后，当前列表会自动刷新。
          </p>
          <div class="creator-delete-actions">
            <button
              type="button"
              class="creator-ghost-button"
              :disabled="deleting"
              @click="emit('cancel')"
            >
              取消
            </button>
            <button
              type="button"
              class="creator-danger-action"
              :disabled="deleting"
              @click="emit('confirm')"
            >
              {{ deleting ? '删除中...' : '确认删除' }}
            </button>
          </div>
        </section>
      </div>
    </Transition>
  </Teleport>
</template>
