import { defineStore } from 'pinia'
import { createConversation, listConversations, getConversationMessages, deleteConversation } from '@/api/agent'

const STATUS_TEXT = { ACTIVE: '进行中', CLOSED: '已结束', ARCHIVED: '已归档' }

function mapConversation(raw) {
  return {
    id: raw.conversationId,
    title: raw.title,
    status: raw.status || 'ACTIVE',
    statusText: STATUS_TEXT[raw.status] || raw.status || '进行中',
    createdAt: raw.createdAt,
    updatedAt: raw.updatedAt,
  }
}

export const useChatStore = defineStore('chat', () => {
  const conversations = ref([])
  const convPage = ref(0)
  const convTotal = ref(0)
  const convLoading = ref(false)
  const currentId = ref('')
  const messages = ref([])
  const msgTotal = ref(0)
  const oldestMsgPage = ref(0)
  const msgLoading = ref(false)
  const streaming = ref(false)
  const awaitingConfirm = ref(false)
  const chatLocked = ref(false)

  const hasMessages = computed(() => messages.value.length > 0)
  const hasMoreConversations = computed(() => conversations.value.length < convTotal.value)
  const hasOlderMessages = computed(() => oldestMsgPage.value > 1)

  /** 拉取云端对话列表下一页（最近活跃在前），append 到侧边栏 */
  async function loadConversations(size = 20) {
    if (convLoading.value) return
    convLoading.value = true
    try {
      const page = convPage.value + 1
      const res = await listConversations(page, size)
      conversations.value.push(...(res?.records || []).map(mapConversation))
      convPage.value = page
      convTotal.value = res?.total ?? conversations.value.length
    } finally {
      convLoading.value = false
    }
  }

  /** 重置并重拉第一页（用于首条消息落地后刷新标题/排序） */
  async function refreshConversations() {
    conversations.value = []
    convPage.value = 0
    convTotal.value = 0
    return loadConversations()
  }

  /** 进入聊天页：加载对话列表并打开最近活跃的对话（回放其历史消息） */
  async function bootstrap() {
    await loadConversations()
    const first = conversations.value[0]
    if (first) await openConversation(first.id)
  }

  /** 打开某个对话：清空本地时间线并回放云端历史（末页优先，只含 USER/ASSISTANT） */
  async function openConversation(id) {
    currentId.value = id
    messages.value = []
    msgTotal.value = 0
    oldestMsgPage.value = 0
    awaitingConfirm.value = false
    chatLocked.value = false
    const res = await getConversationMessages(id, 1, 1)
    const total = res?.total || 0
    if (total > 0) await loadMessagePage(Math.ceil(total / 30))
  }

  /** 前插一页历史消息：page 为 sequenceNo 升序分页中的某一页 */
  async function loadMessagePage(page, size = 30) {
    if (msgLoading.value) return Promise.resolve(false)
    msgLoading.value = true
    try {
      const res = await getConversationMessages(currentId.value, page, size)
      // messageId 是字符串化 Snowflake，绝不能 Number() 转换
      const records = (res?.records || []).map((m) => ({
        id: m.messageId,
        role: m.role === 'USER' ? 'user' : 'assistant',
        content: m.content,
      }))
      messages.value = [...records, ...messages.value]
      oldestMsgPage.value = page
      msgTotal.value = res?.total ?? msgTotal.value
      return true
    } finally {
      msgLoading.value = false
    }
  }

  /** 向上滚动到顶时加载更旧一页 */
  function loadOlderMessages() {
    if (!hasOlderMessages.value) return Promise.resolve(false)
    return loadMessagePage(oldestMsgPage.value - 1)
  }

  /** 删除对话：云端软删除 + 本地移除；若删的是当前对话则清空时间线 */
  async function removeConversation(id) {
    await deleteConversation(id)
    const index = conversations.value.findIndex((c) => c.id === id)
    if (index !== -1) conversations.value.splice(index, 1)
    if (convTotal.value > 0) convTotal.value -= 1
    if (currentId.value === id) {
      currentId.value = ''
      messages.value = []
      msgTotal.value = 0
      oldestMsgPage.value = 0
      awaitingConfirm.value = false
      chatLocked.value = false
    }
  }

  /** 创建云端对话（失败则抛给调用方，不再本地兜底造 id —— 云端才有归属校验） */
  async function newConversation() {
    const res = await createConversation()
    const id = res.conversationId
    const now = new Date().toISOString()
    conversations.value.unshift(mapConversation({ conversationId: id, status: 'ACTIVE', createdAt: now, updatedAt: now }))
    convTotal.value += 1
    switchTo(id)
  }

  function switchTo(id) {
    currentId.value = id
    messages.value = []
    msgTotal.value = 0
    oldestMsgPage.value = 0
    awaitingConfirm.value = false
    chatLocked.value = false
  }

  function addMessage(msg) {
    messages.value.push({ id: Date.now() + Math.random(), ...msg })
  }

  function appendToLast(text) {
    const last = messages.value[messages.value.length - 1]
    if (last && last.role === 'assistant') {
      last.content = (last.content || '') + text
    }
  }

  function updateLastConfirm(payload) {
    const last = messages.value[messages.value.length - 1]
    if (last && last.role === 'confirm') {
      last.content = payload
    }
  }

  function setStreaming(v) {
    streaming.value = v
  }

  function setAwaitingConfirm(v) {
    awaitingConfirm.value = v
  }

  function setChatLocked(v) {
    chatLocked.value = v
  }

  return {
    conversations,
    convPage,
    convTotal,
    convLoading,
    currentId,
    messages,
    msgTotal,
    msgLoading,
    streaming,
    awaitingConfirm,
    chatLocked,
    hasMessages,
    hasMoreConversations,
    hasOlderMessages,
    loadConversations,
    refreshConversations,
    bootstrap,
    openConversation,
    loadOlderMessages,
    newConversation,
    removeConversation,
    switchTo,
    addMessage,
    appendToLast,
    updateLastConfirm,
    setStreaming,
    setAwaitingConfirm,
    setChatLocked,
  }
})
