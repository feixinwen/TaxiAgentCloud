import { createRouter, createWebHistory } from 'vue-router'
import { useUserStore } from '@/stores/user'

const PUBLIC_ROUTES = ['/auth']

const routes = [
  {
    path: '/auth',
    name: 'Auth',
    component: () => import('@/views/auth/AuthView.vue'),
  },
  {
    path: '/',
    component: () => import('@/layouts/MainLayout.vue'),
    children: [
      { path: '', name: 'Home', component: () => import('@/views/HomeView.vue'), meta: { title: '首页' } },
      { path: 'chat', name: 'Chat', component: () => import('@/views/chat/ChatView.vue'), meta: { title: 'AI 对话' } },
      { path: 'orders', name: 'Orders', component: () => import('@/views/OrdersView.vue'), meta: { title: '我的订单' } },
      { path: 'settings', name: 'Settings', component: () => import('@/views/SettingsView.vue'), meta: { title: '设置' } },
    ],
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'NotFound',
    component: () => import('@/views/NotFound.vue'),
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

router.beforeEach((to) => {
  const userStore = useUserStore()
  if (!PUBLIC_ROUTES.includes(to.path) && !userStore.isLoggedIn) {
    return { path: '/auth' }
  }
  if (to.path === '/auth' && userStore.isLoggedIn) {
    return { path: '/' }
  }
  return true
})

export default router
