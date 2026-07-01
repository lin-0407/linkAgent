import { createRouter, createWebHistory } from 'vue-router'

// 首页为产品化入口，工作台通过路由独立访问
const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/',
      name: 'home',
      component: () => import('@/views/HomePage.vue'),
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
  ],
})

export default router
