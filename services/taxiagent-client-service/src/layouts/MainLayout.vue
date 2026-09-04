<template>
  <div class="app-shell">
    <!-- 侧边栏 -->
    <aside class="sidebar">
      <div class="sidebar-logo" @click="$router.push('/')">
        <span class="logo-icon">🚕</span>
        <span class="logo-text">星智出行</span>
      </div>

      <nav class="sidebar-nav">
        <router-link to="/" class="nav-item" active-class="nav-item--active">
          <span>首页</span>
        </router-link>
        <router-link to="/chat" class="nav-item" active-class="nav-item--active">
          <span>AI 对话</span>
        </router-link>
        <router-link to="/orders" class="nav-item" active-class="nav-item--active">
          <span>我的订单</span>
        </router-link>
        <router-link to="/settings" class="nav-item" active-class="nav-item--active">
          <span>设置</span>
        </router-link>
      </nav>

      <div class="sidebar-footer">
        <template v-if="!userStore.isLoggedIn">
          <button class="pill-btn pill-btn--white" @click="$router.push('/auth')">
            登录
          </button>
        </template>
        <template v-else>
          <div class="user-badge">
            <span class="user-role">{{ userStore.role }}</span>
            <span class="user-id">{{ userStore.username || '···' }}</span>
          </div>
          <button class="link-mute" @click="handleLogout">退出登录</button>
        </template>
      </div>
    </aside>

    <!-- 主内容区 -->
    <div class="main-area">
      <header class="topbar">
        <span class="page-title">{{ route.meta.title || '星智出行' }}</span>
      </header>
      <main class="content">
        <router-view />
      </main>
    </div>
  </div>
</template>

<script setup>
import { useRouter, useRoute } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { logoutApi } from '@/api/auth'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()

async function handleLogout() {
  try { await logoutApi() } catch { /* 会话可能已失效 */ }
  userStore.logout()
  router.push('/auth')
}
</script>

<style scoped>
/* ─── Shell layout ─── */
.app-shell {
  display: flex;
  height: 100vh;
  font-family: 'Inter', system-ui, -apple-system, sans-serif;
}

/* ─── Sidebar — DESIGN.md: ink background ─── */
.sidebar {
  width: 220px;
  background: #000000;
  display: flex;
  flex-direction: column;
  flex-shrink: 0;
  border-right: 1px solid #4b4b4b;
}
.sidebar-logo {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 20px 16px;
  cursor: pointer;
}
.logo-icon { font-size: 24px; }
.logo-text {
  font-size: 18px;
  font-weight: 700;
  color: #ffffff;
}

/* Nav items — DESIGN.md: nav-link, ex-app-shell-row */
.sidebar-nav {
  flex: 1;
  padding: 8px;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.nav-item {
  display: flex;
  align-items: center;
  padding: 10px 12px;
  border-radius: 8px;
  font-size: 16px;
  font-weight: 500;
  color: #ffffff;
  text-decoration: none;
  transition: background 0.15s;
}
.nav-item:hover {
  background: rgba(255, 255, 255, 0.08);
}
.nav-item--active {
  background: rgba(255, 255, 255, 0.12);
}

/* Footer area */
.sidebar-footer {
  padding: 16px;
  border-top: 1px solid #4b4b4b;
  display: flex;
  flex-direction: column;
  gap: 8px;
  align-items: center;
}

/* Pill buttons — DESIGN.md */
.pill-btn {
  padding: 10px 20px;
  border: none;
  border-radius: 999px;
  font-size: 16px;
  font-weight: 500;
  font-family: inherit;
  cursor: pointer;
  transition: opacity 0.2s;
}
.pill-btn--white {
  width: 100%;
  background: #ffffff;
  color: #000000;
}
.pill-btn--white:hover {
  opacity: 0.9;
}

.user-badge {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
}
.user-role {
  font-size: 12px;
  font-weight: 500;
  color: #afafaf;
  text-transform: uppercase;
}
.user-id {
  font-size: 12px;
  color: #5e5e5e;
}

.link-mute {
  background: none;
  border: none;
  font-size: 14px;
  color: #4b4b4b;
  cursor: pointer;
  font-family: inherit;
}
.link-mute:hover {
  color: #ffffff;
}

/* ─── Top bar ─── */
.topbar {
  height: 56px;
  background: #ffffff;
  border-bottom: 1px solid #e5e4e7;
  display: flex;
  align-items: center;
  padding: 0 32px;
}
.page-title {
  font-size: 18px;
  font-weight: 500;
  color: #000000;
}

/* ─── Main content ─── */
.main-area {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
}
.content {
  flex: 1;
  overflow-y: auto;
  background: #ffffff;
}

/* ─── Responsive ─── */
@media (max-width: 768px) {
  .sidebar {
    width: 64px;
  }
  .logo-text,
  .nav-item span,
  .user-badge,
  .link-mute {
    display: none;
  }
  .sidebar-logo { justify-content: center; padding: 16px 8px; }
  .nav-item { justify-content: center; }
  .pill-btn--white { font-size: 12px; padding: 8px; }
}
</style>
