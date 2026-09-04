<template>
  <div class="auth-page">
    <!-- 背景装饰插画 -->
    <div class="auth-decor" aria-hidden="true">
      <svg viewBox="0 0 1440 320" fill="none" xmlns="http://www.w3.org/2000/svg" preserveAspectRatio="xMidYMax slice">
        <path d="M0 280 L180 280 L180 220 L260 220 L260 280 L420 280 L420 180 L470 180 L470 150 L560 150 L560 280 L760 280 L760 200 L880 200 L880 280 L1080 280 L1080 170 L1160 170 L1160 120 L1260 120 L1260 280 L1440 280"
              stroke="rgba(48,117,255,0.18)" stroke-width="2" fill="none"/>
        <path d="M0 300 C 360 260, 720 340, 1440 290" stroke="rgba(48,117,255,0.25)" stroke-width="3" fill="none"/>
        <circle cx="1096" cy="96" r="26" fill="rgba(48,117,255,0.14)"/>
        <path d="M1096 78 c-9 0 -16 7 -16 16 c0 11 16 26 16 26 s16 -15 16 -26 c0 -9 -7 -16 -16 -16z" fill="#3075FF" opacity="0.75"/>
        <circle cx="1096" cy="94" r="6" fill="#ffffff"/>
        <circle cx="300" cy="150" r="18" fill="rgba(48,117,255,0.10)"/>
        <path d="M300 136 c-6.5 0 -12 5.5 -12 12 c0 8 12 19 12 19 s12 -11 12 -19 c0 -6.5 -5.5 -12 -12 -12z" fill="#3075FF" opacity="0.55"/>
        <circle cx="300" cy="148" r="4.5" fill="#ffffff"/>
      </svg>
    </div>

    <div class="auth-wrapper">
      <!-- 品牌区 -->
      <div class="brand">
        <span class="brand-icon">🚕</span>
        <span class="brand-name">星智出行</span>
      </div>

      <div class="auth-card">
        <!-- 登录 -->
        <template v-if="!isRegister">
          <div class="mode-tabs">
            <button
              type="button"
              class="mode-tab"
              :class="{ active: loginMode === 'code' }"
              @click="loginMode = 'code'"
            >验证码登录</button>
            <button
              type="button"
              class="mode-tab"
              :class="{ active: loginMode === 'password' }"
              @click="loginMode = 'password'"
            >密码登录</button>
          </div>

          <form class="auth-form" @submit.prevent="handleLogin">
            <!-- 验证码登录 -->
            <template v-if="loginMode === 'code'">
              <div class="field">
                <input
                  v-model="loginForm.email"
                  type="email"
                  class="text-input"
                  placeholder="邮箱地址"
                  required
                />
              </div>
              <div class="field">
                <div class="code-row">
                  <input
                    v-model="loginForm.code"
                    type="text"
                    class="text-input"
                    placeholder="验证码"
                    maxlength="6"
                    required
                  />
                  <button
                    type="button"
                    class="code-btn"
                    :disabled="codeSending || codeCd > 0"
                    @click="sendLoginCode"
                  >{{ codeCd > 0 ? `${codeCd}s 后重发` : '获取验证码' }}</button>
                </div>
              </div>
            </template>

            <!-- 密码登录（后台员工） -->
            <template v-else>
              <div class="field">
                <input
                  v-model="passwordLogin.username"
                  type="text"
                  class="text-input"
                  placeholder="邮箱 / 用户名"
                  required
                />
              </div>
              <div class="field">
                <input
                  v-model="passwordLogin.password"
                  type="password"
                  class="text-input"
                  placeholder="密码"
                  required
                />
              </div>
            </template>

            <button type="submit" class="primary-btn" :disabled="authLoading">
              {{ authLoading ? '登录中…' : '登 录' }}
            </button>

            <button type="button" class="secondary-btn" @click="isRegister = true">注册账号</button>
          </form>

          <div class="aux-row">
            <button type="button" class="text-btn" @click="showForgot = true">忘记密码？</button>
          </div>
        </template>

        <!-- 注册 -->
        <template v-else>
          <h2 class="card-title">创建账号</h2>

          <form class="auth-form" @submit.prevent="handleRegister">
            <!-- 角色选择 -->
            <div class="role-seg">
              <button
                type="button"
                class="role-opt"
                :class="{ active: registerRole === 'USER' }"
                @click="registerRole = 'USER'"
              >乘客</button>
              <button
                type="button"
                class="role-opt"
                :class="{ active: registerRole === 'DRIVER' }"
                @click="registerRole = 'DRIVER'"
              >司机</button>
            </div>

            <div class="field">
              <input
                v-model="registerForm.email"
                type="email"
                class="text-input"
                placeholder="邮箱地址"
                required
              />
            </div>
            <div class="field">
              <div class="code-row">
                <input
                  v-model="registerForm.code"
                  type="text"
                  class="text-input"
                  placeholder="验证码"
                  maxlength="6"
                  required
                />
                <button
                  type="button"
                  class="code-btn"
                  :disabled="codeSending || codeCd > 0"
                  @click="sendRegisterCode"
                >{{ codeCd > 0 ? `${codeCd}s 后重发` : '获取验证码' }}</button>
              </div>
            </div>
            <div class="field">
              <input
                v-model="registerForm.password"
                type="password"
                class="text-input"
                placeholder="设置密码（至少 6 位）"
                required
                minlength="6"
              />
            </div>
            <div class="field">
              <input
                v-model="registerForm.username"
                type="text"
                class="text-input"
                placeholder="用户名（选填）"
                @blur="validateUsername"
              />
              <p v-if="usernameState === 'taken'" class="field-error">该用户名已被占用，换一个试试</p>
            </div>

            <button type="submit" class="primary-btn" :disabled="authLoading">
              {{ authLoading ? '注册中…' : '注 册' }}
            </button>

            <button type="button" class="secondary-btn" @click="isRegister = false">直接登录</button>

            <p class="legal-text">注册即代表您已阅读并同意《服务条款》与《隐私政策》</p>
          </form>
        </template>
      </div>
    </div>

    <el-dialog v-model="showForgot" title="重置密码" width="360px">
      <el-input v-model="forgotForm.email" placeholder="邮箱地址" class="mb8" />
      <div class="forgot-code-row mb8">
        <el-input v-model="forgotForm.code" placeholder="验证码" maxlength="6" />
        <el-button type="primary" plain size="small" @click="sendForgotCode">获取验证码</el-button>
      </div>
      <el-input v-model="forgotForm.newPassword" type="password" placeholder="新密码" show-password />
      <template #footer>
        <el-button round type="primary" :loading="forgotLoading" @click="handleForgot">确认重置</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { useUserStore } from '@/stores/user'
import { useRouter } from 'vue-router'
import {
  loginByEmailCode,
  loginByPassword,
  register,
  checkUsername,
  sendEmailCode,
  resetPassword,
} from '@/api/auth'

const router = useRouter()
const userStore = useUserStore()

const isRegister = ref(false)
const loginMode = ref('code') // 'code' | 'password'
const authLoading = ref(false)
const codeSending = ref(false)
const codeCd = ref(0)

const loginForm = reactive({ email: '', code: '' })
const passwordLogin = reactive({ username: '', password: '' })
const registerForm = reactive({ email: '', code: '', password: '', username: '' })
const registerRole = ref('USER')

const showForgot = ref(false)
const forgotForm = reactive({ email: '', code: '', newPassword: '' })
const forgotLoading = ref(false)

function startCountdown() {
  codeCd.value = 60
  const timer = setInterval(() => {
    codeCd.value--
    if (codeCd.value <= 0) clearInterval(timer)
  }, 1000)
}

async function sendLoginCode() {
  if (!loginForm.email) return ElMessage.warning('请先输入邮箱地址')
  codeSending.value = true
  try {
    await sendEmailCode(loginForm.email, 'LOGIN')
    ElMessage.success('验证码已发送，请查收邮件')
    startCountdown()
  } finally {
    codeSending.value = false
  }
}

async function sendRegisterCode() {
  if (!registerForm.email) return ElMessage.warning('请先输入邮箱地址')
  codeSending.value = true
  try {
    await sendEmailCode(registerForm.email, 'REGISTER')
    ElMessage.success('验证码已发送，请查收邮件')
    startCountdown()
  } finally {
    codeSending.value = false
  }
}

const usernameState = ref(null) // 'available' | 'taken' | null

async function validateUsername() {
  const name = registerForm.username?.trim()
  if (!name || name.includes('@')) return
  try {
    const res = await checkUsername(name)
    usernameState.value = res.available ? 'available' : 'taken'
    if (!res.available) ElMessage.warning('该用户名已被占用，换一个试试')
  } catch {
    usernameState.value = null // 校验失败不阻塞注册——以云端最终校验为准
  }
}

async function handleLogin() {
  authLoading.value = true
  try {
    let res
    if (loginMode.value === 'code') {
      res = await loginByEmailCode(loginForm.email, loginForm.code)
    } else {
      res = await loginByPassword(passwordLogin.username, passwordLogin.password)
    }
    userStore.setSession(res)
    ElMessage.success('欢迎回来')
    router.push('/')
  } finally {
    authLoading.value = false
  }
}

async function handleRegister() {
  authLoading.value = true
  try {
    const res = await register({
      email: registerForm.email,
      code: registerForm.code,
      password: registerForm.password,
      username: registerForm.username || undefined,
      role: registerRole.value,
    })
    userStore.setSession(res)
    ElMessage.success('注册成功，欢迎加入星智出行')
    router.push('/')
  } finally {
    authLoading.value = false
  }
}

async function sendForgotCode() {
  if (!forgotForm.email) return ElMessage.warning('请先输入邮箱地址')
  await sendEmailCode(forgotForm.email, 'RESET_PASSWORD')
  ElMessage.success('验证码已发送，请查收邮件')
}

async function handleForgot() {
  if (!forgotForm.email || !forgotForm.code || !forgotForm.newPassword) {
    return ElMessage.warning('请填写完整信息')
  }
  forgotLoading.value = true
  try {
    await resetPassword(forgotForm)
    ElMessage.success('密码已重置，请重新登录')
    showForgot.value = false
  } finally {
    forgotLoading.value = false
  }
}
</script>

<style scoped>
/* ─── 页面：浅色渐变背景 + 底部装饰插画 ─── */
.auth-page {
  position: relative;
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(180deg, #e8f0ff 0%, #f4f7fc 45%, #ffffff 100%);
  font-family: 'PingFang SC', 'Microsoft YaHei', 'Inter', system-ui, -apple-system, sans-serif;
  color: #1f2329;
  overflow: hidden;
  padding: 32px 16px;
  box-sizing: border-box;
}
.auth-decor {
  position: absolute;
  inset: auto 0 0 0;
  pointer-events: none;
}
.auth-decor svg {
  display: block;
  width: 100%;
  height: 240px;
}

.auth-wrapper {
  position: relative;
  width: 100%;
  max-width: 400px;
}

/* ─── 品牌区 ─── */
.brand {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  margin-bottom: 24px;
}
.brand-icon { font-size: 30px; }
.brand-name {
  font-size: 26px;
  font-weight: 700;
  color: #1f2329;
  letter-spacing: 1px;
}

/* ─── 卡片 ─── */
.auth-card {
  background: #ffffff;
  border-radius: 16px;
  box-shadow: 0 8px 32px rgba(31, 60, 122, 0.10);
  padding: 32px;
}

.card-title {
  font-size: 20px;
  font-weight: 700;
  color: #1f2329;
  margin: 0 0 20px;
}

/* ─── 登录方式 Tab（下划线式） ─── */
.mode-tabs {
  display: flex;
  gap: 28px;
  margin-bottom: 24px;
}
.mode-tab {
  position: relative;
  background: none;
  border: none;
  padding: 4px 2px 10px;
  font-size: 16px;
  font-family: inherit;
  color: #86909c;
  cursor: pointer;
  transition: color 0.2s;
}
.mode-tab.active {
  color: #1f2329;
  font-weight: 600;
}
.mode-tab.active::after {
  content: '';
  position: absolute;
  left: 50%;
  bottom: 0;
  transform: translateX(-50%);
  width: 24px;
  height: 3px;
  border-radius: 2px;
  background: #3075ff;
}

/* ─── 表单 ─── */
.auth-form {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.field {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.field-error {
  margin: 0;
  font-size: 12px;
  color: #f53f3f;
  line-height: 1.5;
}

/* 输入框：白底描边 */
.text-input {
  width: 100%;
  height: 44px;
  padding: 0 14px;
  background: #ffffff;
  border: 1px solid #e0e0e0;
  border-radius: 8px;
  font-size: 15px;
  font-family: inherit;
  color: #1f2329;
  outline: none;
  box-sizing: border-box;
  transition: border-color 0.2s, box-shadow 0.2s;
}
.text-input:focus {
  border-color: #3075ff;
  box-shadow: 0 0 0 3px rgba(48, 117, 255, 0.12);
}
.text-input::placeholder {
  color: #a8abb2;
}

.code-row {
  display: flex;
  align-items: center;
  gap: 8px;
}
.code-row .text-input { flex: 1; }
.code-btn {
  flex-shrink: 0;
  background: none;
  border: none;
  padding: 8px 4px;
  font-size: 14px;
  font-family: inherit;
  color: #3075ff;
  cursor: pointer;
  white-space: nowrap;
}
.code-btn:hover:not(:disabled) { color: #1f63f0; }
.code-btn:disabled {
  color: #a8abb2;
  cursor: not-allowed;
}

/* ─── 主按钮 ─── */
.primary-btn {
  height: 44px;
  border: none;
  border-radius: 8px;
  background: #3075ff;
  color: #ffffff;
  font-size: 16px;
  font-weight: 500;
  font-family: inherit;
  letter-spacing: 6px;
  text-indent: 6px; /* 视觉居中补偿 */
  cursor: pointer;
  transition: background 0.2s;
  margin-top: 8px;
}
.primary-btn:hover:not(:disabled) { background: #1f63f0; }
.primary-btn:active:not(:disabled) { background: #1a56d6; }
.primary-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

/* ─── 次要按钮（注册/登录切换） ─── */
.secondary-btn {
  height: 44px;
  border: 1px solid #3075ff;
  border-radius: 8px;
  background: #ffffff;
  color: #3075ff;
  font-size: 16px;
  font-weight: 500;
  font-family: inherit;
  letter-spacing: 6px;
  text-indent: 6px;
  cursor: pointer;
  transition: background 0.2s;
}
.secondary-btn:hover { background: rgba(48, 117, 255, 0.06); }

/* ─── 辅助行 ─── */
.aux-row {
  display: flex;
  align-items: center;
  justify-content: center;
  margin-top: 16px;
}
.text-btn {
  background: none;
  border: none;
  padding: 0;
  font-size: 14px;
  font-family: inherit;
  color: #86909c;
  cursor: pointer;
}
.text-btn:hover { color: #3075ff; }

/* ─── 角色分段控件 ─── */
.role-seg {
  display: flex;
  background: #f2f3f5;
  border-radius: 8px;
  padding: 4px;
  gap: 4px;
}
.role-opt {
  flex: 1;
  height: 36px;
  border: none;
  border-radius: 6px;
  background: transparent;
  font-size: 14px;
  font-family: inherit;
  color: #86909c;
  cursor: pointer;
  transition: all 0.2s;
}
.role-opt.active {
  background: #ffffff;
  color: #3075ff;
  font-weight: 600;
  box-shadow: 0 1px 4px rgba(31, 60, 122, 0.12);
}

.legal-text {
  margin: 0;
  font-size: 12px;
  color: #a8abb2;
  line-height: 1.6;
  text-align: center;
}

/* ─── 忘记密码弹窗 ─── */
.mb8 { margin-bottom: 8px; }
.forgot-code-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

/* ─── 响应式 ─── */
@media (max-width: 480px) {
  .auth-card { padding: 24px 20px; }
  .auth-decor svg { height: 140px; }
}
</style>
