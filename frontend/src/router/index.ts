import { createRouter, createWebHistory } from 'vue-router'

// 展示页已移除，访问根地址直接进入可以开始工作的创作台。
const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  // 跨页面进入创作台时回到顶部，避免上一页滚动位置让移动端吸附流程栏压住任务操作。
  scrollBehavior(_to, _from, savedPosition) {
    return savedPosition ?? { left: 0, top: 0 }
  },
  routes: [
    {
      path: '/',
      redirect: '/creator',
    },
    {
      path: '/creator',
      name: 'creator',
      // 懒加载，降低首屏打包体积
      component: () => import('@/components/CreatorWorkspace.vue'),
    },
    {
      path: '/knowledge',
      name: 'knowledge',
      component: () => import('@/components/KnowledgeWorkspace.vue'),
    },
    {
      path: '/projects',
      name: 'projects',
      component: () => import('@/components/ProjectListWorkspace.vue'),
    },
    {
      // P0-3: 视频分析页 — 展示已绑定任务的视频卡片并支持分析追问
      path: '/video-analysis',
      name: 'videoAnalysis',
      component: () => import('@/views/VideoAnalysisPage.vue'),
    },
    {
      // P1-2: 长期记忆管理页 — 查看/搜索/删除系统自动提取的长期记忆
      path: '/memory',
      name: 'memory',
      component: () => import('@/views/MemoryManagementPage.vue'),
    },
    {
      path: '/usage-logs',
      name: 'usageLogs',
      component: () => import('@/views/UsageLogPage.vue'),
    },
    {
      path: '/:pathMatch(.*)*',
      name: 'notFound',
      component: () => import('@/views/NotFoundPage.vue'),
    },
  ],
})

export default router
