import request from './request'
import { useUserStore } from '@/stores/user'

const AUTH_HEADER = () => {
  const token = useUserStore().session?.accessToken || ''
  return { 'Content-Type': 'application/json', Accept: 'text/event-stream', Authorization: `Bearer ${token}` }
}

/** POST /api/agent/conversations → 201 {conversationId,status,createdAt} */
export function createConversation() {
  return request.post('/agent/conversations')
}

/** GET /api/agent/conversations?page&size - 最近活跃在前 */
export function listConversations(page = 1, size = 10) {
  return request.get('/agent/conversations', { params: { page, size } })
}

/** GET /api/agent/conversations/{id}/messages?page&size - 历史消息，sequenceNo 升序（page 1 为最旧一页） */
export function getConversationMessages(conversationId, page = 1, size = 30) {
  return request.get(`/agent/conversations/${conversationId}/messages`, { params: { page, size } })
}

/** DELETE /api/agent/conversations/{id} - 软删除，204；重删幂等仍 204 */
export function deleteConversation(conversationId) {
  return request.delete(`/agent/conversations/${conversationId}`)
}

/** POST /api/agent/conversations/{id}/messages - 建立单次 Run SSE 流 */
export function sendMessage(conversationId, content) {
  return fetch(`/api/agent/conversations/${conversationId}/messages`, {
    method: 'POST',
    headers: AUTH_HEADER(),
    body: JSON.stringify({ clientMessageId: crypto.randomUUID(), content }),
  })
}

/** POST /api/agent/conversations/{id}/resume - HITL 确认后恢复 */
export function resumeConversation(conversationId, content) {
  return fetch(`/api/agent/conversations/${conversationId}/resume`, {
    method: 'POST',
    headers: AUTH_HEADER(),
    body: JSON.stringify({ clientMessageId: crypto.randomUUID(), content }),
  })
}
