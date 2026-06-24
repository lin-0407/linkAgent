<script setup lang="ts">
defineProps<{
  target: 'prePublish' | 'feedback' | null
  title: string
}>()

const prePublishGuidance = defineModel<string>('prePublishGuidance')
const feedbackGuidance = defineModel<string>('feedbackGuidance')

const emit = defineEmits<{
  close: []
  reset: []
  backdropPointerDown: [event: PointerEvent]
  backdropClick: [event: MouseEvent]
}>()
</script>

<template>
  <div
    v-if="target"
    class="creator-modal-backdrop"
    role="presentation"
    @pointerdown="emit('backdropPointerDown', $event)"
    @click="emit('backdropClick', $event)"
  >
    <section class="creator-prompt-modal" role="dialog" aria-modal="true" :aria-label="title">
      <header>
        <div>
          <p class="creator-kicker">业务指导</p>
          <h3>{{ title }}</h3>
        </div>
        <button type="button" class="creator-ghost-button" @click="emit('close')">关闭</button>
      </header>

      <label v-if="target === 'prePublish'" class="creator-prompt-field">
        <span>可调整的风格与建议偏好</span>
        <textarea v-model="prePublishGuidance" maxlength="2000" placeholder="可补充固定风格；留空沿用后端基础规则"></textarea>
      </label>
      <label v-else class="creator-prompt-field">
        <span>可调整的风格与分析偏好</span>
        <textarea v-model="feedbackGuidance" maxlength="2000" placeholder="可补充固定复盘口径；留空沿用后端基础规则"></textarea>
      </label>

      <p class="creator-prompt-hint">
        可描述表达风格、建议侧重点和分析顺序；角色、数据边界及基础输出结构由系统统一维护。
      </p>

      <footer>
        <button type="button" class="creator-secondary-action" @click="emit('reset')">恢复默认指导</button>
        <button type="button" class="creator-primary-button" @click="emit('close')">保存并关闭</button>
      </footer>
    </section>
  </div>
</template>
