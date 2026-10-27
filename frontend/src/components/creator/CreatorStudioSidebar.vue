<script setup lang="ts">
import {
  Check,
  Clapperboard,
  FileText,
  FolderOpen,
  ListChecks,
  MessageSquareText,
  MonitorPlay,
  Plus,
  ScanLine,
} from '@lucide/vue'
import type { CreatorTaskSummary } from '@/types/creator'

type StepKey = 'task' | 'prePublish' | 'production' | 'preflight' | 'feedback' | 'report'
type Step = { key: StepKey; label: string; description: string }
defineProps<{
  activeStep: string
  selected: boolean
  tasks: CreatorTaskSummary[]
  selectedTaskId: string
  steps: Step[]
  canNavigate: (step: StepKey) => boolean
  isCompleted: (step: StepKey) => boolean
  statusLabel: (status: CreatorTaskSummary['status']) => string
}>()
const emit = defineEmits<{
  navigate: [step: StepKey]
  select: [taskId: string]
  create: []
  browse: []
}>()
const icons = {
  task: FileText,
  prePublish: ListChecks,
  production: Clapperboard,
  preflight: MonitorPlay,
  feedback: MessageSquareText,
  report: ScanLine,
}
const labels = {
  task: '内容与资料',
  prePublish: '发布方案',
  production: '视频制作',
  preflight: '成片试映',
  feedback: '观众反馈',
  report: '创作复盘',
}
function changeStep(event: Event) {
  emit('navigate', (event.target as HTMLSelectElement).value as StepKey)
}
</script>

<template>
  <aside class="studio-sidebar" :class="{ 'is-project': selected }" aria-label="作品与工作区">
    <div class="studio-sidebar-heading">
      <span>{{ selected ? '本期工作' : '你的作品' }}</span>
      <button
        type="button"
        class="studio-icon-button"
        aria-label="新建创作"
        title="新建创作"
        @click="emit('create')"
      >
        <Plus :size="17" />
      </button>
    </div>
    <label v-if="selected" class="studio-mobile-stage">
      <span class="sr-only">切换创作区域</span>
      <select :value="activeStep" @change="changeStep">
        <option
          v-for="step in steps"
          :key="step.key"
          :value="step.key"
          :disabled="!canNavigate(step.key)"
        >
          {{ labels[step.key] }}
        </option>
      </select>
    </label>
    <nav v-if="selected" class="studio-stage-navigation" aria-label="创作工作区">
      <button
        v-for="step in steps"
        :key="step.key"
        type="button"
        :class="{ active: activeStep === step.key, completed: isCompleted(step.key) }"
        :disabled="!canNavigate(step.key)"
        :aria-current="activeStep === step.key ? 'step' : undefined"
        :title="step.description"
        @click="emit('navigate', step.key)"
      >
        <component :is="icons[step.key]" :size="18" :stroke-width="1.6" aria-hidden="true" />
        <span>{{ labels[step.key] }}</span>
        <Check
          v-if="isCompleted(step.key)"
          :size="13"
          class="studio-stage-check"
          aria-label="已完成"
        />
        <span
          v-else-if="activeStep === step.key"
          class="studio-current-dot"
          aria-hidden="true"
        ></span>
      </button>
    </nav>
    <div class="studio-library" :class="{ 'studio-library-secondary': selected }">
      <div v-if="selected" class="studio-library-label">切换作品</div>
      <button
        v-for="task in tasks"
        :key="task.taskId"
        type="button"
        class="studio-project-entry"
        :class="{ active: task.taskId === selectedTaskId }"
        :title="task.taskName"
        @click="emit('select', task.taskId)"
      >
        <span class="studio-project-dot" aria-hidden="true"></span>
        <span
          ><strong>{{ task.taskName }}</strong
          ><small>{{ statusLabel(task.status) }}</small></span
        >
      </button>
      <p v-if="!tasks.length" class="studio-library-empty">第一部作品，从一个想法开始。</p>
      <button type="button" class="studio-library-browse" @click="emit('browse')">
        <FolderOpen :size="15" aria-hidden="true" />浏览全部作品
      </button>
    </div>
    <div class="studio-sidebar-foot">
      <span class="studio-orbit" aria-hidden="true"></span><span>每次创作，都有迹可循。</span>
    </div>
  </aside>
</template>
