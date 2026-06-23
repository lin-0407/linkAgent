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
  ],
})

export default router
