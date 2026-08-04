import { nextTick, onBeforeUnmount, ref, watch, type Ref } from 'vue'

const FOCUSABLE_SELECTOR = [
  'a[href]',
  'button:not([disabled])',
  'input:not([disabled])',
  'select:not([disabled])',
  'textarea:not([disabled])',
  '[contenteditable="true"]',
  '[tabindex]:not([tabindex="-1"])',
].join(',')

let pageLockCount = 0
let previousBodyOverflow = ''
let appRootWasInert = false

function lockBackground() {
  const appRoot = document.getElementById('app')
  if (pageLockCount === 0) {
    previousBodyOverflow = document.body.style.overflow
    appRootWasInert = appRoot?.hasAttribute('inert') ?? false
    document.body.style.overflow = 'hidden'
    appRoot?.setAttribute('inert', '')
  }
  pageLockCount += 1
}

function unlockBackground() {
  if (pageLockCount === 0) return
  pageLockCount -= 1
  if (pageLockCount > 0) return

  document.body.style.overflow = previousBodyOverflow
  const appRoot = document.getElementById('app')
  if (!appRootWasInert) appRoot?.removeAttribute('inert')
}

/**
 * 为 Teleport 弹层补齐焦点圈定、Escape、关闭回焦和背景隔离。
 * 弹层仍保留各自业务结构，只共享必须一致的键盘行为。
 */
export function useModalDialog(open: Ref<boolean>, close: () => void) {
  const dialogRef = ref<HTMLElement | null>(null)
  let returnFocus: HTMLElement | null = null
  let backgroundLocked = false

  watch(open, async (isOpen) => {
    if (isOpen) {
      returnFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null
      lockBackground()
      backgroundLocked = true
      await nextTick()
      const initialFocus = dialogRef.value?.querySelector<HTMLElement>('[data-dialog-initial-focus]')
      ;(initialFocus ?? dialogRef.value)?.focus()
      return
    }
    releaseBackground()
    returnFocus?.focus()
    returnFocus = null
  })

  onBeforeUnmount(() => {
    releaseBackground()
    returnFocus?.focus()
  })

  function releaseBackground() {
    if (!backgroundLocked) return
    backgroundLocked = false
    unlockBackground()
  }

  function handleDialogKeydown(event: KeyboardEvent) {
    if (event.key === 'Escape') {
      event.preventDefault()
      close()
      return
    }
    if (event.key !== 'Tab' || !dialogRef.value) return

    const focusable = Array.from(
      dialogRef.value.querySelectorAll<HTMLElement>(FOCUSABLE_SELECTOR),
    ).filter((element) => element.offsetParent !== null)
    if (focusable.length === 0) {
      event.preventDefault()
      dialogRef.value.focus()
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

  return { dialogRef, handleDialogKeydown }
}
