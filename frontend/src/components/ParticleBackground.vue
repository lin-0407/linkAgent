<script setup lang="ts">
// ============================================================================
// ParticleBackground — 灵感粒子网络背景层
//
// 全屏 fixed Canvas，pointer-events: none 保证下方内容可交互。
// 粒子渲染在所有页面内容之上（z-index: 100），密度极低且透明，
// 不会影响文字可读性。
// ============================================================================

import { ref, onMounted, onUnmounted, watch } from 'vue'
import { useParticleNetwork } from '@/composables/useParticleNetwork'
import type { ParticleNetworkOptions } from '@/composables/useParticleNetwork'

const props = withDefaults(defineProps<ParticleNetworkOptions>(), {
  density: 12000,
  linkDist: 135,
  enabled: true,
})

const canvasRef = ref<HTMLCanvasElement | null>(null)
const { mount, unmount, setEnabled } = useParticleNetwork(canvasRef, {
  density: props.density,
  linkDist: props.linkDist,
  enabled: props.enabled,
})

watch(() => props.enabled, (val) => setEnabled(val))

onMounted(() => mount())
onUnmounted(() => unmount())
</script>

<template>
  <canvas
    ref="canvasRef"
    class="particle-bg-canvas"
    aria-hidden="true"
  />
</template>

<style scoped>
.particle-bg-canvas {
  position: fixed;
  top: 0;
  left: 0;
  width: 100%;
  height: 100%;
  /* 最底层，只有空白/透明区域才会透出粒子，面板遮挡处自然不可见 */
  z-index: 0;
  pointer-events: none;
}
</style>
