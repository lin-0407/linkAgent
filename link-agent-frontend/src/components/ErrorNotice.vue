<script setup lang="ts">
defineProps<{
  errorMessage: string
}>()

function formatErrorMessage(value: string) {
  if (value.startsWith('HTTP')) {
    return '请求没有成功返回，优先检查后端服务是否启动，以及代理接口是否可访问。'
  }

  return '请求过程中出现异常，可以稍后重试，或查看浏览器控制台定位网络问题。'
}
</script>

<template>
  <div v-if="errorMessage" class="error" role="alert">
    <strong>这次请求没有完成</strong>
    <span>{{ formatErrorMessage(errorMessage) }}</span>
    <code>{{ errorMessage }}</code>
  </div>
</template>
