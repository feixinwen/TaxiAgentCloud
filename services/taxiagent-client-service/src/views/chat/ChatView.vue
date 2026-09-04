<template>
  <div class="chat-shell">
    <!-- ── 左侧会话列表 ── -->
    <aside class="chat-sidebar">
      <button class="new-chat-pill" @click="handleNewChat">
        <span class="plus">+</span> 新对话
      </button>

      <div class="conv-list" v-if="chatStore.conversations.length" ref="convListRef" @scroll="onConvListScroll">
        <button
          v-for="conv in chatStore.conversations"
          :key="conv.id"
          class="conv-item"
          :class="{ active: conv.id === chatStore.currentId }"
          @click="handleSelectConversation(conv.id)"
        >
          <span class="conv-title">{{ conv.title || formatConvTime(conv) }}</span>
          <span class="conv-status">{{ conv.statusText }}</span>
          <span class="conv-del" title="删除对话" @click.stop="handleDeleteConversation(conv)">×</span>
        </button>
        <div v-if="chatStore.convLoading" class="conv-more-hint">加载中…</div>
        <button
          v-else-if="chatStore.hasMoreConversations"
          class="conv-more-btn"
          @click="chatStore.loadConversations()"
        >加载更多</button>
      </div>
      <div v-else class="conv-empty">
        <p>{{ chatStore.convLoading ? '加载中…' : '暂无会话' }}</p>
      </div>
    </aside>

    <!-- ── 右侧对话区 ── -->
    <div class="chat-main">
      <!-- 空状态 -->
      <div v-if="!chatStore.currentId" class="chat-empty">
        <div class="empty-illustration">
          <svg viewBox="0 0 200 160" fill="none" xmlns="http://www.w3.org/2000/svg">
            <rect x="60" y="20" width="80" height="14" rx="7" fill="#efefef" />
            <rect x="30" y="46" width="140" height="14" rx="7" fill="#efefef" />
            <circle cx="100" cy="100" r="28" stroke="#000" stroke-width="1.5" />
            <path d="M92 100h16M100 92v16" stroke="#000" stroke-width="1.5" stroke-linecap="round" />
          </svg>
        </div>
        <p class="empty-text">发起对话，即可叫车出行</p>
      </div>

      <!-- 消息列表 -->
      <template v-else>
        <!-- 断线横幅：服务端 Run 断开即记 CANCELLED，重连仅恢复输入焦点 -->
        <div v-if="connectionLost" class="conn-banner">
          <span>连接已断开，上方历史消息已保留。</span>
          <button type="button" class="banner-retry" @click="retryConnection">重新连接</button>
        </div>

        <div class="messages-area" ref="scrollRef" @scroll="onMessagesScroll">
          <div class="messages-inner">
            <div
              v-for="msg in chatStore.messages"
              :key="msg.id"
              class="msg-row"
              :class="`msg-${msg.role}`"
            >
              <!-- 用户消息 -->
              <template v-if="msg.role === 'user'">
                <div class="bubble bubble-user">{{ msg.content }}</div>
              </template>

              <!-- AI 消息 -->
              <template v-else-if="msg.role === 'assistant'">
                <div class="bubble bubble-ai">{{ msg.content }}</div>
              </template>

              <!-- 确认卡片 -->
              <template v-else-if="msg.role === 'confirm'">
                <div class="confirm-card">
                  <h3 class="confirm-title">确认行程</h3>
                  <div class="confirm-details">
                    <div class="confirm-route">
                      <div class="route-point">
                        <span class="route-dot route-dot--start"></span>
                        <span class="route-text">{{ getConfirmField(msg.content, '上车点') }}</span>
                      </div>
                      <div class="route-line"></div>
                      <div class="route-point">
                        <span class="route-dot route-dot--end"></span>
                        <span class="route-text">{{ getConfirmField(msg.content, '目的地') }}</span>
                      </div>
                    </div>
                    <div class="confirm-meta">
                      <div v-for="line in formatConfirm(msg.content)" :key="line.label" class="meta-row" v-show="line.label !== '上车点' && line.label !== '目的地'">
                        <span class="meta-label">{{ line.label }}</span>
                        <span class="meta-value" :class="{ 'meta-price': line.label === '预估价格' }">{{ line.value }}</span>
                      </div>
                    </div>
                  </div>
                  <div class="confirm-actions">
                    <button
                      class="pill-btn pill-btn--secondary"
                      :disabled="!chatStore.awaitingConfirm"
                      @click="handleConfirm('cancelled', msg.id)"
                    >取消</button>
                    <button
                      class="pill-btn pill-btn--primary"
                      :disabled="!chatStore.awaitingConfirm"
                      @click="handleConfirm('confirmed', msg.id)"
                    >确认下单</button>
                  </div>
                </div>
              </template>

              <!-- 系统通知 -->
              <template v-else-if="msg.role === 'notify'">
                <p class="notify-text">{{ msg.content }}</p>
              </template>

              <!-- 错误 -->
              <template v-else-if="msg.role === 'error'">
                <p class="error-text">{{ msg.content }}</p>
              </template>
            </div>

            <!-- 打字指示器 -->
            <div v-if="chatStore.streaming && !streamingText" class="typing-row">
              <div class="typing-dots">
                <span></span><span></span><span></span>
              </div>
            </div>

            <div ref="bottomSentinel"></div>
          </div>
        </div>

        <!-- 输入栏 -->
        <div class="input-bar">
          <input
            ref="inputRef"
            v-model="inputText"
            type="text"
            class="chat-input"
            placeholder="您要去哪儿？"
            :disabled="chatStore.streaming || chatStore.awaitingConfirm"
            @keydown.enter="handleSend"
          />
          <button
            class="pill-btn pill-btn--primary send-btn"
            :disabled="!inputText.trim() || chatStore.streaming || chatStore.awaitingConfirm"
            @click="handleSend"
          >发送</button>
        </div>
      </template>
    </div>

    <!-- ── 右侧常驻地图（加载失败/无 key 自动隐藏降级纯聊天） ── -->
    <aside v-if="mapState !== 'failed'" class="map-panel" :class="{ 'map-panel--collapsed': mapCollapsed }">
      <div class="map-toolbar">
        <span class="map-toolbar-title">地图</span>
        <button
          class="map-pill"
          :disabled="mapState !== 'ready' || locating"
          @click="handleLocateMe"
        >{{ locating ? '定位中…' : '一键定位' }}</button>
        <button
          class="map-pill"
          :class="{ 'map-pill--active': chatMap.pickMode.value }"
          :disabled="mapState !== 'ready'"
          @click="togglePickMode"
        >{{ chatMap.pickMode.value ? '取消点选' : '设定我的位置' }}</button>
        <button class="map-collapse" @click="toggleMapCollapsed">
          {{ mapCollapsed ? '展开地图' : '收起地图' }}
        </button>
      </div>
      <div v-show="!mapCollapsed" ref="mapElRef" class="map-container">
        <div v-if="mapState === 'loading'" class="map-loading">地图加载中…</div>
      </div>
    </aside>
  </div>
</template>

<script setup>
import { useChatStore } from '@/stores/chat'
import { useUserStore } from '@/stores/user'
import { sendMessage, resumeConversation } from '@/api/agent'
import { createSseParser } from '@/api/sseParser'
import { getUserLocation, saveUserLocation } from '@/api/user'
import { getOrderDetail, getOrderRoute, getOngoingOrders } from '@/api/order'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useChatMap } from '@/composables/useChatMap'

const FALLBACK_CENTER = [121.4737, 31.2304] // 上海人民广场：定位被拒/不可用时的地图中心

const chatStore = useChatStore()
const userStore = useUserStore()

// ── Chat map ──
const mapElRef = ref(null)
const mapState = ref('loading') // loading | ready | failed
const mapCollapsed = ref(typeof window !== 'undefined' && !!window.matchMedia?.('(max-width: 768px)').matches)
const chatMap = useChatMap(mapElRef)

async function initMap() {
  const ok = await chatMap.bootstrapMap(null)
  mapState.value = ok ? 'ready' : 'failed'
  if (!ok) return
  await initUserLocation()
  replayStoredRoute(chatStore.currentId)
}

async function initUserLocation() {
  let point = null
  try {
    const loc = await getUserLocation()
    if (loc?.latitude != null && loc?.longitude != null) {
      point = [Number(loc.longitude), Number(loc.latitude)]
    }
  } catch { /* 404 从未设置 → 走浏览器定位 */ }
  if (!point) {
    const pos = await chatMap.locateCurrentPosition()
    if (pos) point = [pos.lng, pos.lat]
  }
  chatMap.setUserLocation(point || FALLBACK_CENTER)
}

function togglePickMode() {
  if (mapState.value !== 'ready') return
  if (chatMap.pickMode.value) {
    chatMap.stopPickMode()
    return
  }
  chatMap.startPickMode(onMapPicked)
}

async function onMapPicked(lng, lat) {
  chatMap.stopPickMode()
  const address = await chatMap.reverseGeocode(lng, lat)
  try {
    await saveUserLocation({ latitude: String(lat), longitude: String(lng), address })
    chatMap.setUserLocation([lng, lat])
    ElMessage.success('位置已更新')
  } catch { /* request 拦截器已提示 */ }
}

// 一键定位：浏览器定位 → 逆地理 → 存云端（agent 下单时作为默认起点）
const locating = ref(false)

async function handleLocateMe() {
  if (mapState.value !== 'ready' || locating.value) return
  locating.value = true
  try {
    const pos = await chatMap.locateCurrentPosition()
    if (!pos) {
      ElMessage.warning('定位失败，请检查浏览器定位权限，或点选地图设置位置')
      return
    }
    const address = await chatMap.reverseGeocode(pos.lng, pos.lat)
    await saveUserLocation({ latitude: String(pos.lat), longitude: String(pos.lng), address })
    chatMap.setUserLocation([pos.lng, pos.lat])
    ElMessage.success(address ? `已定位：${address}` : '位置已更新')
  } catch { /* request 拦截器已提示 */ } finally {
    locating.value = false
  }
}

// ── conversationId → traceId 本地存档（历史回放用，换设备/清缓存不画） ──
const ROUTE_TRACE_KEY = 'taxiagent:chat:routeTraces'

function readTraceMap() {
  try { return JSON.parse(localStorage.getItem(ROUTE_TRACE_KEY)) || {} } catch { return {} }
}
function saveRouteTrace(convId, traceId) {
  try {
    const map = readTraceMap()
    map[convId] = traceId
    localStorage.setItem(ROUTE_TRACE_KEY, JSON.stringify(map))
  } catch { /* 存储不可用时静默 */ }
}
function readRouteTrace(convId) {
  return readTraceMap()[convId] || null
}
function removeRouteTrace(convId) {
  try {
    const map = readTraceMap()
    delete map[convId]
    localStorage.setItem(ROUTE_TRACE_KEY, JSON.stringify(map))
  } catch { /* 存储不可用时静默 */ }
}

let replaySeq = 0
/** 重开对话时回放路线：realPolyline 有值画橙线（行程已结束），否则绿线估价路线 */
async function replayStoredRoute(convId) {
  if (mapState.value !== 'ready' || !convId) return
  const traceId = readRouteTrace(convId)
  if (!traceId) return
  const seq = ++replaySeq
  try {
    const route = await getOrderRoute(traceId, { skipErrorMessage: true })
    if (seq !== replaySeq || convId !== chatStore.currentId) return
    if (route?.realPolyline) chatMap.showRealRoute(route.realPolyline)
    else chatMap.showEstimateRoute({ polyline: route?.estPolyline })
  } catch (e) {
    if (seq !== replaySeq) return
    if (e?.response?.status === 404 || e?.response?.status === 403) {
      if (e?.response?.status === 404) removeRouteTrace(convId)
      ElMessage.info('该行程的路线数据不可用')
    }
  }
}

/** confirm 事件 → 地图画估价路线；traceId 缺失（旧事件/异常）时仅画 marker，不影响确认流程 */
function drawConfirmRoute(payload) {
  if (mapState.value !== 'ready') return
  const convId = chatStore.currentId
  const start = toPoint(payload?.startLng, payload?.startLat)
  const end = toPoint(payload?.endLng, payload?.endLat)
  const traceId = payload?.traceId

  if (start || end) chatMap.showEstimateRoute({ start: start || undefined, end: end || undefined })
  if (!traceId) return

  if (convId) saveRouteTrace(convId, traceId)
  getOrderRoute(traceId, { skipErrorMessage: true })
    .then((route) => {
      if (convId === chatStore.currentId && route?.estPolyline) {
        chatMap.showEstimateRoute({ start, end, polyline: route.estPolyline })
      }
    })
    .catch(() => { /* 轨迹暂时取不到时保留 marker */ })
}

function toPoint(lng, lat) {
  const nLng = Number(lng)
  const nLat = Number(lat)
  return Number.isFinite(nLng) && Number.isFinite(nLat) ? [nLng, nLat] : null
}

// 置为 true 于确认下单后；run.completed 到达时消费并同步实际路线
let pendingOrderCheck = false

/** 下单 run 结束后：查进行中订单 → realRoute 非空切橙线，否则保留估价绿线 */
async function syncOrderRouteAfterCreate() {
  if (mapState.value !== 'ready') return
  try {
    const ids = await getOngoingOrders({ skipErrorMessage: true })
    const orderId = Array.isArray(ids) ? ids[0] : null
    if (!orderId) return
    const detail = await getOrderDetail(orderId, { skipErrorMessage: true })
    if (detail?.realRoute) chatMap.showRealRoute(detail.realRoute)
  } catch { /* 路线同步是非关键路径，静默 */ }
}

function toggleMapCollapsed() {
  mapCollapsed.value = !mapCollapsed.value
  if (!mapCollapsed.value) nextTick(() => chatMap.resize())
}

const scrollRef = ref(null)
const bottomSentinel = ref(null)
const inputRef = ref(null)
const inputText = ref('')
const streamingText = ref(false)
const abortController = ref(null)
const connectionLost = ref(false)

// ── Auto-scroll ──
function scrollToBottom(smooth = true) {
  nextTick(() => {
    bottomSentinel.value?.scrollIntoView({ behavior: smooth ? 'smooth' : 'instant' })
  })
}

// ── Send message ──
async function handleSend() {
  const text = inputText.value.trim()
  if (!text || chatStore.streaming || chatStore.awaitingConfirm) return

  inputText.value = ''
  connectionLost.value = false
  chatStore.addMessage({ role: 'user', content: text })

  if (!userStore.isLoggedIn) {
    chatStore.addMessage({ role: 'error', content: '请先登录' })
    return
  }

  // 确保有当前会话
  if (!chatStore.currentId) {
    await chatStore.newConversation()
  }

  chatStore.setStreaming(true)
  streamingText.value = false

  const controller = new AbortController()
  abortController.value = controller

  try {
    const resp = await sendMessage(chatStore.currentId, text)
    if (!resp.ok) {
      const errBody = await resp.json().catch(() => ({}))
      throw new Error(errBody.message || `HTTP ${resp.status}`)
    }
    const reader = resp.body.getReader()
    const parser = createSseParser()

    // tool.started/notify 插行后最后一条不再是 assistant——按状态判断而非一次性 flag，
    // 否则工具后轮次的正文 delta 会被 appendToLast 静默丢弃
    const ensureAssistant = () => {
      const last = chatStore.messages[chatStore.messages.length - 1]
      if (last?.role === 'assistant') return
      chatStore.addMessage({ role: 'assistant', content: '' })
    }

    while (true) {
      const { done, value } = await reader.read()
      if (done) {
        const remaining = parser.flush()
        for (const ev of remaining) {
          processEvent(ev, ensureAssistant)
          await nextTick()
        }
        break
      }
      const events = parser.feed(value)
      for (const ev of events) {
        processEvent(ev, ensureAssistant)
        // yield to Vue for per-token rendering — 15ms is imperceptible but guarantees DOM flush
        await new Promise(r => setTimeout(r, 15))
      }
    }
  } catch (e) {
    if (e.name !== 'AbortError') {
      connectionLost.value = true
      chatStore.addMessage({ role: 'error', content: e.message })
    }
  } finally {
    chatStore.setStreaming(false)
    streamingText.value = false
    abortController.value = null
    refreshTitleIfNeeded()
  }
}

// 首条消息落地后服务端才生成标题——流结束后对无标题会话重拉第一页列表
function refreshTitleIfNeeded() {
  const conv = chatStore.conversations.find((c) => c.id === chatStore.currentId)
  if (conv?.title || !chatStore.messages.length) return
  chatStore.refreshConversations().catch(() => {})
}

const RUN_FAILED_TEXT = {
  MODEL_UNAVAILABLE: '模型暂不可用，请稍后重试',
  RUN_TIMEOUT: '回复超时，请重新发送',
  RAG_UNAVAILABLE: '知识检索暂时不可用',
  RAG_REQUEST_REJECTED: '知识检索请求被拒绝',
  TOOL_NOT_ALLOWED: '工具调用不被允许',
  TOOL_LIMIT_EXCEEDED: '工具调用次数超限',
  INTERNAL_ERROR: '服务器内部错误',
  ORDER_REQUEST_REJECTED: '下单请求被拒绝',
  ORDER_UNAVAILABLE: '订单服务暂不可用',
}

function processEvent(event, ensureAssistant) {
  const payload = event.data || {}
  switch (event.event) {
    case 'message.delta':
      ensureAssistant()
      chatStore.appendToLast(payload.content)
      streamingText.value = true
      break
    case 'tool.started':
    case 'tool.completed':
      // 工具调用对用户不可见，不打断消息时间线
      break
    case 'notify':
      chatStore.addMessage({ role: 'notify', content: payload.content })
      break
    case 'confirm':
      chatStore.addMessage({ role: 'confirm', content: payload.content })
      chatStore.setAwaitingConfirm(true)
      drawConfirmRoute(payload)
      break
    case 'run.failed': {
      const text = RUN_FAILED_TEXT[payload.errorCode] || '服务异常'
      chatStore.addMessage({ role: 'error', content: payload.message ? `${text}: ${payload.message}` : text })
      break
    }
    case 'run.completed':
      if (pendingOrderCheck) {
        pendingOrderCheck = false
        syncOrderRouteAfterCreate()
      }
      break
    default:
      // run.started / message.completed / heartbeat —— 无渲染动作
      break
  }
  scrollToBottom()
}

// ── Confirm / Cancel ──
async function handleConfirm(feedback) {
  chatStore.setAwaitingConfirm(false)
  chatStore.setStreaming(true)
  streamingText.value = false
  if (feedback === 'confirmed') pendingOrderCheck = true

  const controller = new AbortController()
  abortController.value = controller

  try {
    const resp = await resumeConversation(chatStore.currentId, feedback)
    if (!resp.ok) {
      const errBody = await resp.json().catch(() => ({}))
      throw new Error(errBody.message || `HTTP ${resp.status}`)
    }
    const reader = resp.body.getReader()
    const parser = createSseParser()

    const ensureAssistant = () => {
      const last = chatStore.messages[chatStore.messages.length - 1]
      if (last?.role === 'assistant') return
      chatStore.addMessage({ role: 'assistant', content: '' })
    }
    while (true) {
      const { done, value } = await reader.read()
      if (done) {
        const remaining = parser.flush()
        for (const ev of remaining) {
          processEvent(ev, ensureAssistant)
          await nextTick()
        }
        break
      }
      const events = parser.feed(value)
      for (const ev of events) {
        processEvent(ev, ensureAssistant)
        await new Promise(r => setTimeout(r, 15))
      }
    }
  } catch (e) {
    if (e.name !== 'AbortError') {
      connectionLost.value = true
      chatStore.addMessage({ role: 'error', content: e.message })
    }
  } finally {
    chatStore.setStreaming(false)
    streamingText.value = false
    abortController.value = null
    pendingOrderCheck = false
  }
}

function retryConnection() {
  connectionLost.value = false
  inputRef.value?.focus()
}

function getConfirmField(content, label) {
  const lines = formatConfirm(content)
  const found = lines.find(l => l.label === label)
  return found ? found.value : '—'
}

// ── New chat ──
async function handleNewChat() {
  if (abortController.value) abortController.value.abort()
  await chatStore.newConversation()
  nextTick(() => inputRef.value?.focus())
}

// ── 会话切换 ──
const convListRef = ref(null)
const loadingOlder = ref(false)

// ── 删除会话：确认弹窗 → 云端软删除 → 本地移除（当前对话则清空时间线与路线存档） ──
async function handleDeleteConversation(conv) {
  try {
    await ElMessageBox.confirm('删除后该对话将从列表移除，且不可恢复。', '删除对话', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  if (conv.id === chatStore.currentId && abortController.value) {
    abortController.value.abort()
    abortController.value = null
    chatStore.setStreaming(false)
    streamingText.value = false
  }
  try {
    await chatStore.removeConversation(conv.id)
    removeRouteTrace(conv.id)
    ElMessage.success('对话已删除')
  } catch { /* request 拦截器已提示 */ }
}

async function handleSelectConversation(id) {
  if (id === chatStore.currentId) return
  // 切换前中断在途 SSE 流，避免流事件写入新对话的时间线
  if (abortController.value) {
    abortController.value.abort()
    abortController.value = null
    chatStore.setStreaming(false)
    streamingText.value = false
  }
  try {
    await chatStore.openConversation(id)
  } catch { /* request 拦截器已提示 */ }
}

function onConvListScroll() {
  const el = convListRef.value
  if (!el || chatStore.convLoading || !chatStore.hasMoreConversations) return
  if (el.scrollTop + el.clientHeight >= el.scrollHeight - 40) {
    chatStore.loadConversations()
  }
}

function formatConvTime(conv) {
  const t = new Date(conv.updatedAt || conv.createdAt).getTime()
  if (Number.isNaN(t)) return '对话'
  const diff = Date.now() - t
  const pad = (v) => String(v).padStart(2, '0')
  if (diff < 60_000) return '刚刚'
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)} 分钟前`
  if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)} 小时前`
  const d = new Date(t)
  const now = new Date()
  const yesterday = new Date(now)
  yesterday.setDate(now.getDate() - 1)
  const sameDay = (a, b) =>
    a.getFullYear() === b.getFullYear() && a.getMonth() === b.getMonth() && a.getDate() === b.getDate()
  if (sameDay(d, yesterday)) return `昨天 ${pad(d.getHours())}:${pad(d.getMinutes())}`
  if (d.getFullYear() === now.getFullYear()) return `${d.getMonth() + 1}月${d.getDate()}日`
  return `${d.getFullYear()}年${d.getMonth() + 1}月${d.getDate()}日`
}

// ── 向上滚动近顶：前插更旧一页并保持滚动位置不跳 ──
async function onMessagesScroll() {
  const el = scrollRef.value
  if (!el || loadingOlder.value || chatStore.msgLoading || !chatStore.hasOlderMessages) return
  if (el.scrollTop > 60) return

  loadingOlder.value = true
  const prevHeight = el.scrollHeight
  const prevTop = el.scrollTop
  try {
    await chatStore.loadOlderMessages()
  } catch { /* request 拦截器已提示 */ } finally {
    loadingOlder.value = false
  }
  await nextTick()
  el.scrollTop = el.scrollHeight - prevHeight + prevTop
}

// ── Format confirm payload（云端 camelCase 摘要）──
function formatConfirm(raw) {
  try {
    const obj = typeof raw === 'string' ? JSON.parse(raw) : raw
    if (!obj || typeof obj !== 'object') return String(raw)

    const lines = []
    const add = (label, value) => { if (value != null && value !== '') lines.push({ label, value }) }

    // Route info
    add('上车点', obj.startAddress)
    add('目的地', obj.endAddress)
    // Price info
    if (obj.estPrice != null) add('预估价格', `¥${Number(obj.estPrice).toFixed(2)}`)
    if (obj.estDistance != null) add('距离', `${Number(obj.estDistance).toFixed(1)} 公里`)
    // Vehicle
    add('车型', obj.vehicleType)
    // Other
    add('预约单', obj.isReservation ? '是' : '否')
    add('加急单', obj.isExpedited ? '是' : '否')
    if (obj.scheduledTime) add('出发时间', obj.scheduledTime)

    return lines
  } catch {
    return []
  }
}

// ── 进入页面：加载云端对话列表并回放最近活跃对话的历史消息；地图与聊天并行初始化 ──
onMounted(() => {
  if (!chatStore.currentId) {
    chatStore.bootstrap().catch(() => { /* 服务不可用则等首次发送时再建会话 */ })
  }
  initMap()
})

// ── 切换对话：清掉上一段路线，按本地存档回放该对话的路线 ──
watch(
  () => chatStore.currentId,
  (id, old) => {
    if (old !== undefined) chatMap.clearRoute()
    replayStoredRoute(id)
  },
)

// ── Watch scroll on messages change ──
watch(
  () => chatStore.messages.length,
  (n, o) => {
    // 前插更旧一页时不得滚到底；会话切换清空也无需滚动
    if (loadingOlder.value || n < o) return
    scrollToBottom(false)
  },
)
</script>

<style scoped>
/* ─── Shell ─── */
.chat-shell {
  display: flex;
  height: 100%;
  font-family: 'Inter', system-ui, -apple-system, sans-serif;
}

/* ─── Sidebar ─── */
.chat-sidebar {
  width: 240px;
  background: #000000;
  display: flex;
  flex-direction: column;
  padding: 16px;
  flex-shrink: 0;
  border-right: 1px solid #4b4b4b;
}

/* New chat pill — DESIGN.md: button-primary on dark */
.new-chat-pill {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  width: 100%;
  padding: 10px 0;
  background: #ffffff;
  color: #000000;
  border: none;
  border-radius: 999px;
  font-size: 16px;
  font-weight: 500;
  font-family: inherit;
  cursor: pointer;
  margin-bottom: 16px;
  transition: opacity 0.15s;
}
.new-chat-pill:hover { opacity: 0.85; }
.plus { font-size: 20px; font-weight: 400; }

.conv-list {
  flex: 1;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.conv-item {
  width: 100%;
  padding: 10px 28px 10px 12px;
  background: transparent;
  border: none;
  border-radius: 8px;
  color: #ffffff;
  font-size: 14px;
  font-weight: 400;
  font-family: inherit;
  cursor: pointer;
  text-align: left;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 2px;
  transition: background 0.15s;
  position: relative;
}
.conv-item:hover { background: rgba(255, 255, 255, 0.08); }
.conv-item.active { background: rgba(255, 255, 255, 0.12); }
.conv-title {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  display: block;
  max-width: 100%;
}
.conv-status {
  font-size: 12px;
  color: #afafaf;
}
.conv-del {
  position: absolute;
  right: 6px;
  top: 50%;
  transform: translateY(-50%);
  width: 20px;
  height: 20px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  color: #afafaf;
  font-size: 15px;
  line-height: 1;
  opacity: 0;
  transition: opacity 0.15s, background 0.15s, color 0.15s;
}
.conv-item:hover .conv-del,
.conv-del:focus-visible { opacity: 1; }
.conv-del:hover { background: rgba(255, 255, 255, 0.16); color: #ffffff; }
.conv-more-btn {
  width: 100%;
  margin-top: 4px;
  padding: 8px 0;
  background: rgba(255, 255, 255, 0.08);
  border: none;
  border-radius: 8px;
  color: #ffffff;
  font-size: 13px;
  font-family: inherit;
  cursor: pointer;
  transition: background 0.15s;
}
.conv-more-btn:hover { background: rgba(255, 255, 255, 0.14); }
.conv-more-hint {
  padding: 8px 0;
  text-align: center;
  font-size: 12px;
  color: #5e5e5e;
}

.conv-empty {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #5e5e5e;
  font-size: 14px;
}

/* ─── Main area ─── */
.chat-main {
  flex: 1;
  display: flex;
  flex-direction: column;
  background: #ffffff;
  min-width: 0;
}

/* Empty state */
.chat-empty {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 16px;
}
.empty-illustration { opacity: 0.5; }
.empty-text {
  font-size: 16px;
  color: #afafaf;
}

/* ─── Connection banner ─── */
.conn-banner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin: 12px 32px 0;
  max-width: 680px;
  padding: 10px 16px;
  background: #efefef;
  border-radius: 8px;
  font-size: 13px;
  color: #5e5e5e;
}
.banner-retry {
  border: none;
  background: transparent;
  font-size: 13px;
  font-weight: 500;
  color: #000000;
  cursor: pointer;
  font-family: inherit;
  text-decoration: underline;
}

/* ─── Messages ─── */
.messages-area {
  flex: 1;
  overflow-y: auto;
  padding: 24px 32px;
}
.messages-inner {
  max-width: 680px;
  margin: 0 auto;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

/* Message rows */
.msg-row {
  display: flex;
  margin-bottom: 4px;
}
.msg-user { justify-content: flex-end; }
.msg-assistant { justify-content: flex-start; }
.msg-confirm { justify-content: center; margin: 12px 0; }
.msg-notify, .msg-error { justify-content: center; }

/* Bubbles */
.bubble {
  max-width: 80%;
  padding: 12px 16px;
  font-size: 16px;
  line-height: 1.5;
  word-break: break-word;
}
.bubble-user {
  background: #282828;
  color: #ffffff;
  border-radius: 20px 20px 4px 20px;
}
.bubble-ai {
  background: #ffffff;
  color: #000000;
  border: 1px solid #e5e4e7;
  border-radius: 20px 20px 20px 4px;
}

/* Notify & error text */
.notify-text {
  font-size: 13px;
  color: #afafaf;
  padding: 2px 0;
}
.error-text {
  font-size: 13px;
  color: #5e5e5e;
  background: #efefef;
  padding: 8px 14px;
  border-radius: 8px;
}

/* ─── Confirmation card — DESIGN.md: card-elevated ─── */
.confirm-card {
  width: 100%;
  max-width: 380px;
  background: #ffffff;
  border: 1px solid #e5e4e7;
  border-radius: 16px;
  padding: 24px;
  box-shadow: rgba(0, 0, 0, 0.12) 0px 4px 16px 0px;
}
.confirm-title {
  font-size: 20px;
  font-weight: 700;
  color: #000000;
  margin-bottom: 20px;
}

.confirm-details {
  display: flex;
  flex-direction: column;
  gap: 20px;
  margin-bottom: 24px;
}

/* Route visual */
.confirm-route {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.route-point {
  display: flex;
  align-items: center;
  gap: 12px;
}
.route-dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  flex-shrink: 0;
}
.route-dot--start { background: #000000; }
.route-dot--end { background: #000000; border: 2px solid #000000; background: #ffffff; }
.route-line {
  width: 1px;
  height: 16px;
  background: #e5e4e7;
  margin-left: 4px;
}
.route-text {
  font-size: 16px;
  font-weight: 500;
  color: #000000;
}

/* Meta rows */
.confirm-meta {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding-top: 16px;
  border-top: 1px solid #efefef;
}
.meta-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.meta-label {
  font-size: 14px;
  color: #5e5e5e;
}
.meta-value {
  font-size: 16px;
  font-weight: 500;
  color: #000000;
}
.meta-price {
  font-size: 20px;
  font-weight: 700;
  color: #000000;
}

.confirm-actions {
  display: flex;
  gap: 10px;
}

/* Pill buttons — DESIGN.md */
.pill-btn {
  padding: 12px 24px;
  border: none;
  border-radius: 999px;
  font-size: 16px;
  font-weight: 500;
  font-family: inherit;
  cursor: pointer;
  transition: opacity 0.15s;
  flex: 1;
}
.pill-btn:disabled { opacity: 0.4; cursor: not-allowed; }
.pill-btn--primary {
  background: #000000;
  color: #ffffff;
}
.pill-btn--primary:hover:not(:disabled) { background: #282828; }
.pill-btn--secondary {
  background: #efefef;
  color: #000000;
}
.pill-btn--secondary:hover:not(:disabled) { background: #e2e2e2; }

/* ─── Typing indicator ─── */
.typing-row {
  display: flex;
  padding: 4px 0;
}
.typing-dots {
  display: flex;
  gap: 4px;
  padding: 8px 14px;
  background: #ffffff;
  border: 1px solid #e5e4e7;
  border-radius: 20px 20px 20px 4px;
}
.typing-dots span {
  width: 7px;
  height: 7px;
  background: #afafaf;
  border-radius: 50%;
  animation: dotPulse 1.4s infinite ease-in-out;
}
.typing-dots span:nth-child(2) { animation-delay: 0.2s; }
.typing-dots span:nth-child(3) { animation-delay: 0.4s; }
@keyframes dotPulse {
  0%, 60%, 100% { opacity: 0.3; transform: scale(0.75); }
  30% { opacity: 1; transform: scale(1); }
}

/* ─── Input bar ─── */
.input-bar {
  display: flex;
  gap: 10px;
  padding: 16px 32px 24px;
  max-width: 744px;
  margin: 0 auto;
  width: 100%;
}
.chat-input {
  flex: 1;
  padding: 14px 18px;
  background: #efefef;
  border: none;
  border-radius: 999px;
  font-size: 16px;
  font-family: inherit;
  color: #000000;
  outline: none;
  transition: background 0.2s;
}
.chat-input:focus {
  background: #f3f3f3;
  box-shadow: inset 0 0 0 1px #000000;
}
.chat-input::placeholder { color: #afafaf; }
.chat-input:disabled { opacity: 0.6; }

.send-btn {
  padding: 14px 28px;
  flex-shrink: 0;
}

/* ─── Map panel ─── */
.map-panel {
  width: 38%;
  min-width: 320px;
  max-width: 520px;
  display: flex;
  flex-direction: column;
  background: #ffffff;
  border-left: 1px solid #e5e4e7;
  flex-shrink: 0;
}
.map-toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 12px;
}
.map-toolbar-title {
  flex: 1;
  font-size: 14px;
  font-weight: 600;
  color: #000000;
}
.map-pill {
  padding: 8px 14px;
  border: none;
  border-radius: 999px;
  background: #000000;
  color: #ffffff;
  font-size: 13px;
  font-family: inherit;
  cursor: pointer;
  transition: opacity 0.15s;
}
.map-pill:hover:not(:disabled) { opacity: 0.85; }
.map-pill:disabled { opacity: 0.4; cursor: not-allowed; }
.map-pill--active {
  background: #efefef;
  color: #000000;
  box-shadow: inset 0 0 0 1px #000000;
}
.map-collapse {
  padding: 8px 12px;
  border: 1px solid #e5e4e7;
  border-radius: 999px;
  background: #ffffff;
  color: #000000;
  font-size: 13px;
  font-family: inherit;
  cursor: pointer;
  transition: background 0.15s;
}
.map-collapse:hover { background: #efefef; }
.map-container {
  flex: 1;
  position: relative;
}
.map-loading {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #efefef;
  color: #afafaf;
  font-size: 13px;
}
.map-panel--collapsed .map-container { display: none; }

/* ─── Responsive ─── */
@media (max-width: 768px) {
  .chat-sidebar { display: none; }
  .chat-shell { flex-direction: column; }
  .messages-area { padding: 16px; }
  .input-bar { padding: 12px 16px 16px; }
  .bubble { max-width: 90%; }

  /* 移动端：地图收为聊天顶部可展开卡片 */
  .map-panel {
    order: -1;
    width: 100%;
    min-width: 0;
    max-width: none;
    border-left: none;
    border-bottom: 1px solid #e5e4e7;
  }
  .map-container {
    flex: none;
    height: 240px;
  }
}
</style>
