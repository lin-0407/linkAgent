import { onBeforeUnmount, onMounted, watch } from 'vue'
import type { Ref } from 'vue'

export function useReferenceVideoPageSize(
  gridRef: Ref<HTMLElement | null>,
  onPageSizeChange: (size: number) => void,
) {
  let resizeObserver: ResizeObserver | null = null
  let resizeTimer: ReturnType<typeof setTimeout> | undefined
  let lastWidth = 0
  let lastPageSize = 0

  function updatePageSize() {
    const grid = gridRef.value
    if (!grid || grid.clientWidth === 0) return

    const style = window.getComputedStyle(grid)
    // 直接读取实际网格列数，避免脚本断点与 CSS 不一致后再次出现缺一格。
    const columns = style.gridTemplateColumns.split(/\s+/).length
    const gap = Number.parseFloat(style.columnGap) || 0
    const cardWidth = (grid.clientWidth - gap * (columns - 1)) / columns
    // 封面为 16:9，下方预留标题和操作区；按稳定尺寸估算，避免换页内容引发反复请求。
    const estimatedRowHeight = cardWidth * 9 / 16 + 190 + gap
    const chromeHeight = (Number.parseFloat(style.getPropertyValue('--surface-topbar-height')) || 0) + 36
    const rows = Math.min(4, Math.max(
      columns === 1 ? 3 : 2,
      Math.ceil((window.innerHeight - chromeHeight) / estimatedRowHeight),
    ))
    const size = columns * rows
    if (size === lastPageSize) return
    lastPageSize = size
    onPageSizeChange(size)
  }

  function scheduleUpdate() {
    clearTimeout(resizeTimer)
    // 拖动窗口期间只在停稳后更新分页，避免每个像素变化都请求列表。
    resizeTimer = setTimeout(updatePageSize, 180)
  }

  watch(gridRef, (grid) => {
    resizeObserver?.disconnect()
    if (grid) {
      resizeObserver?.observe(grid)
      scheduleUpdate()
    }
  }, { flush: 'post' })

  onMounted(() => {
    resizeObserver = new ResizeObserver(([entry]) => {
      if (!entry || Math.abs(entry.contentRect.width - lastWidth) < 1) return
      lastWidth = entry.contentRect.width
      scheduleUpdate()
    })
    if (gridRef.value) resizeObserver.observe(gridRef.value)
    window.addEventListener('resize', scheduleUpdate)
    updatePageSize()
  })

  onBeforeUnmount(() => {
    clearTimeout(resizeTimer)
    resizeObserver?.disconnect()
    window.removeEventListener('resize', scheduleUpdate)
  })
}
