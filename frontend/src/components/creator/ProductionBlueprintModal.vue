<script setup lang="ts">
import { nextTick, onBeforeUnmount, ref, watch } from 'vue'
import ProductionStepDetail from './ProductionStepDetail.vue'
import ProductionStepList from './ProductionStepList.vue'
import type {
  ProductionPlan,
  ProductionStep,
  ProductionStepStatus,
} from '@/types/creatorProduction'

const props = defineProps<{
  open: boolean
  plan: ProductionPlan | null
  steps: ProductionStep[]
  selectedStep: ProductionStep | null
  selectedStepId: string
  readyForMedia: boolean
  busy: boolean
  errorMessage: string
}>()

const emit = defineEmits<{
  close: []
  restart: []
  select: [stepId: string]
  update: [status: ProductionStepStatus, rowVersion: number, skipReason?: string]
}>()

const dialog = ref<HTMLElement | null>(null)
let returnFocus: HTMLElement | null = null
let backdropPointerDown = false

watch(
  () => props.open,
  async (open) => {
    if (open) {
      returnFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null
      await nextTick()
      dialog.value?.focus()
      return
    }
    returnFocus?.focus()
    returnFocus = null
  },
)

onBeforeUnmount(() => {
  returnFocus?.focus()
})

function handleBackdropPointerDown(event: PointerEvent) {
  backdropPointerDown = event.target === event.currentTarget
}

function handleBackdropClick(event: MouseEvent) {
  if (backdropPointerDown && event.target === event.currentTarget) {
    emit('close')
  }
  backdropPointerDown = false
}

function handleDialogKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape') {
    event.preventDefault()
    emit('close')
    return
  }
  if (event.key !== 'Tab' || !dialog.value) return

  const focusable = Array.from(
    dialog.value.querySelectorAll<HTMLElement>(
      'button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])',
    ),
  )
  if (focusable.length === 0) {
    event.preventDefault()
    dialog.value.focus()
    return
  }

  const first = focusable[0]
  const last = focusable[focusable.length - 1]
  if (event.shiftKey && document.activeElement === first) {
    event.preventDefault()
    last?.focus()
  } else if (!event.shiftKey && document.activeElement === last) {
    event.preventDefault()
    first?.focus()
  }
}

function handleStepUpdate(status: ProductionStepStatus, rowVersion: number, skipReason?: string) {
  emit('update', status, rowVersion, skipReason)
}
</script>

<template>
  <Teleport to="body">
    <Transition name="production-modal">
      <div
        v-if="open && plan"
        class="creator-modal-backdrop production-modal-backdrop"
        role="presentation"
        @pointerdown="handleBackdropPointerDown"
        @click="handleBackdropClick"
      >
        <section
          ref="dialog"
          :class="['production-blueprint-modal', { 'has-error': errorMessage }]"
          role="dialog"
          aria-modal="true"
          aria-labelledby="production-blueprint-title"
          aria-describedby="production-blueprint-summary"
          tabindex="-1"
          @keydown="handleDialogKeydown"
        >
          <header class="production-modal-header">
            <div>
              <p class="production-modal-kicker">
                {{ plan.videoCategory === 'AI_GENERATED' ? 'AI 视频' : '项目演示' }} · V{{
                  plan.planVersion
                }}
              </p>
              <h2 id="production-blueprint-title">{{ plan.planTitle || '制作蓝图' }}</h2>
              <p id="production-blueprint-summary">{{ plan.positioningSummary }}</p>
            </div>
            <button
              type="button"
              class="production-modal-close"
              aria-label="关闭制作蓝图"
              title="关闭"
              @click="emit('close')"
            >
              <svg viewBox="0 0 24 24" aria-hidden="true">
                <path d="M6 6l12 12M18 6L6 18" />
              </svg>
            </button>
          </header>

          <p v-if="errorMessage" class="production-modal-error" role="alert">
            {{ errorMessage }}
          </p>

          <div class="production-modal-body">
            <ProductionStepList
              :steps="steps"
              :selected-step-id="selectedStepId"
              @select="emit('select', $event)"
            />
            <ProductionStepDetail :step="selectedStep" :busy="busy" @update="handleStepUpdate" />
          </div>

          <footer class="production-modal-footer">
            <p :class="{ ready: readyForMedia }">
              <span aria-hidden="true"></span>
              {{
                readyForMedia
                  ? '制作步骤已完成，可以进入成片试映。'
                  : '完成或跳过全部步骤后，才能进入成片试映。'
              }}
            </p>
            <button
              type="button"
              class="production-restart-action"
              :disabled="busy"
              @click="emit('restart')"
            >
              重新定位
            </button>
          </footer>
        </section>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.production-blueprint-modal {
  display: grid;
  grid-template-rows: auto minmax(0, 1fr) auto;
  width: min(1180px, 100%);
  height: min(780px, calc(100vh - 48px));
  overflow: hidden;
  color: var(--text);
  background: var(--surface);
  border: 1px solid var(--border-strong);
  border-radius: var(--r);
  box-shadow: var(--sh-lg);
  outline: none;
}

.production-blueprint-modal.has-error {
  grid-template-rows: auto auto minmax(0, 1fr) auto;
}

.production-modal-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 24px;
  padding: 24px 26px 20px;
  border-bottom: 1px solid var(--border);
}

.production-modal-header > div {
  min-width: 0;
}

.production-modal-kicker {
  margin: 0 0 5px;
  color: var(--accent-strong);
  font-size: 12px;
  font-weight: var(--fw-bold);
}

.production-modal-header h2 {
  margin: 0;
  color: var(--ink);
  font-size: 23px;
  letter-spacing: 0;
}

.production-modal-header p:last-child {
  max-width: 760px;
  margin: 7px 0 0;
  color: var(--muted);
  line-height: 1.6;
}

.production-modal-close {
  display: grid;
  flex: 0 0 auto;
  width: 44px;
  height: 44px;
  padding: 0;
  place-items: center;
  color: var(--muted);
  background: transparent;
  border: 1px solid transparent;
  border-radius: var(--r-sm);
  cursor: pointer;
  transition:
    color 180ms ease,
    background 180ms ease,
    border-color 180ms ease;
}

.production-modal-close:hover {
  color: var(--ink);
  background: var(--surface-sub);
  border-color: var(--border);
}

.production-modal-close:focus-visible,
.production-restart-action:focus-visible {
  outline: 3px solid var(--accent-ring);
  outline-offset: 2px;
}

.production-modal-close svg {
  width: 20px;
  height: 20px;
  fill: none;
  stroke: currentColor;
  stroke-linecap: round;
  stroke-width: 1.8;
}

.production-modal-error {
  margin: 0;
  padding: 10px 26px;
  color: var(--danger);
  background: rgba(220, 38, 38, 0.06);
  border-bottom: 1px solid rgba(220, 38, 38, 0.14);
  font-size: 13px;
}

.production-modal-body {
  display: grid;
  grid-template-columns: minmax(230px, 0.32fr) minmax(0, 1fr);
  min-height: 0;
  overflow: hidden;
}

.production-modal-footer {
  display: flex;
  min-height: 62px;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 10px 26px;
  border-top: 1px solid var(--border);
}

.production-modal-footer p {
  display: flex;
  align-items: center;
  gap: 9px;
  margin: 0;
  color: var(--warn);
  font-size: 13px;
}

.production-modal-footer p > span {
  width: 8px;
  height: 8px;
  background: currentColor;
  border-radius: 50%;
}

.production-modal-footer p.ready {
  color: var(--ok);
}

.production-restart-action {
  min-height: 44px;
  padding: 0 12px;
  color: var(--muted);
  background: transparent;
  border: 1px solid transparent;
  border-radius: var(--r-sm);
  cursor: pointer;
}

.production-restart-action:hover {
  color: var(--ink);
  background: var(--surface-sub);
}

.production-restart-action:disabled {
  opacity: 0.5;
  cursor: wait;
}

.production-modal-enter-active,
.production-modal-leave-active {
  transition: opacity 180ms ease;
}

.production-modal-enter-active .production-blueprint-modal,
.production-modal-leave-active .production-blueprint-modal {
  transition:
    opacity 180ms ease,
    transform 180ms ease;
}

.production-modal-enter-from,
.production-modal-leave-to,
.production-modal-enter-from .production-blueprint-modal,
.production-modal-leave-to .production-blueprint-modal {
  opacity: 0;
}

.production-modal-enter-from .production-blueprint-modal,
.production-modal-leave-to .production-blueprint-modal {
  transform: translateY(10px);
}

@media (max-width: 800px) {
  .production-modal-backdrop {
    padding: 0;
    place-items: end center;
  }

  .production-blueprint-modal {
    width: 100%;
    height: min(92vh, 860px);
    border-right: 0;
    border-bottom: 0;
    border-left: 0;
    border-radius: var(--r) var(--r) 0 0;
  }

  .production-modal-header {
    padding: 18px 16px 16px;
  }

  .production-modal-header h2 {
    font-size: 20px;
  }

  .production-modal-header p:last-child {
    display: none;
  }

  .production-modal-body {
    grid-template-columns: 1fr;
    grid-template-rows: auto minmax(0, 1fr);
  }

  .production-modal-footer {
    align-items: flex-start;
    padding: 10px 16px;
  }
}

@media (max-width: 520px) {
  .production-modal-footer {
    display: grid;
  }

  .production-restart-action {
    justify-self: start;
  }
}

@media (prefers-reduced-motion: reduce) {
  .production-modal-enter-active,
  .production-modal-leave-active,
  .production-modal-enter-active .production-blueprint-modal,
  .production-modal-leave-active .production-blueprint-modal {
    transition: none;
  }
}
</style>
