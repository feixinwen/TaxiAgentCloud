<template>
  <div class="orders-page">
    <h2 class="page-heading">我的订单</h2>

    <!-- 状态筛选药丸 -->
    <div class="filter-row">
      <button
        v-for="f in filters"
        :key="f.key"
        class="filter-pill"
        :class="{ active: activeFilter === f.key }"
        @click="activeFilter = f.key"
      >{{ f.label }}</button>
    </div>

    <!-- 加载中 -->
    <div v-if="loading" class="loading-state">
      <p>订单加载中…</p>
    </div>

    <!-- 空状态 -->
    <div v-else-if="!orders.length" class="empty-state">
      <svg viewBox="0 0 120 100" fill="none" xmlns="http://www.w3.org/2000/svg">
        <rect x="20" y="10" width="80" height="80" rx="12" stroke="#e5e4e7" stroke-width="1.5" />
        <rect x="35" y="35" width="50" height="8" rx="4" fill="#efefef" />
        <rect x="35" y="50" width="30" height="6" rx="3" fill="#efefef" />
      </svg>
      <p class="empty-text">暂无订单</p>
      <button class="pill-btn pill-btn--black" @click="$router.push('/chat')">去叫车</button>
    </div>

    <!-- 订单列表 -->
    <div v-else class="order-list">
      <div
        v-for="order in orders"
        :key="order.orderId"
        class="order-card"
        :class="{ expanded: expandedId === order.orderId }"
        @click="toggleDetail(order)"
      >
        <!-- 卡片主体 -->
        <div class="card-main">
          <div class="card-route">
            <div class="route-visual">
              <span class="route-dot start"></span>
              <span class="route-line"></span>
              <span class="route-dot end"></span>
            </div>
            <div class="route-addresses">
              <p class="addr start">{{ order.startAddress || '—' }}</p>
              <p class="addr end">{{ order.endAddress || '—' }}</p>
            </div>
          </div>
          <div class="card-meta">
            <span class="status-badge" :class="statusClass(order.orderStatus)">
              {{ statusLabel(order.orderStatus) }}
            </span>
            <span class="price">{{ formatPrice(order.estPrice || order.realPrice) }}</span>
          </div>
        </div>

        <!-- 展开详情 -->
        <div v-if="expandedId === order.orderId" class="card-detail">
          <div class="detail-grid">
            <div class="detail-item">
              <span class="detail-label">订单号</span>
              <span class="detail-value mono">{{ order.orderId }}</span>
            </div>
            <div class="detail-item">
              <span class="detail-label">车型</span>
              <span class="detail-value">{{ vehicleName(order.vehicleType) }}</span>
            </div>
            <div class="detail-item" v-if="order.estDistance">
              <span class="detail-label">距离</span>
              <span class="detail-value">{{ Number(order.estDistance).toFixed(1) }} 公里</span>
            </div>
            <div class="detail-item">
              <span class="detail-label">下单时间</span>
              <span class="detail-value">{{ formatTime(order.createTime) }}</span>
            </div>
            <div class="detail-item" v-if="order.cancelReason">
              <span class="detail-label">取消原因</span>
              <span class="detail-value">{{ order.cancelReason }}</span>
            </div>
          </div>
          <div class="detail-actions" v-if="canCancel(order.orderStatus)">
            <button class="pill-btn pill-btn--subtle" @click.stop="handleCancel(order)">取消订单</button>
          </div>
          <div class="detail-actions" v-if="order.orderStatus === 50">
            <button class="pill-btn pill-btn--black" @click.stop="handlePay(order)">立即支付</button>
          </div>
        </div>
      </div>

      <!-- 分页 -->
      <div class="pagination" v-if="total > pageSize">
        <button class="pill-btn pill-btn--subtle" :disabled="page <= 1" @click="loadPage(page - 1)">上一页</button>
        <span class="page-info">{{ page }} / {{ Math.ceil(total / pageSize) }}</span>
        <button class="pill-btn pill-btn--subtle" :disabled="page * pageSize >= total" @click="loadPage(page + 1)">下一页</button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { getOrders, getOrderDetail, cancelOrder, payOrder } from '@/api/order'
import { useUserStore } from '@/stores/user'

const userStore = useUserStore()
const orders = ref([])
const loading = ref(false)
const page = ref(1)
const pageSize = 20
const total = ref(0)
const activeFilter = ref('all')
const expandedId = ref(null)

const filters = [
  { key: 'all', label: '全部' },
  { key: 'active', label: '进行中' },
  { key: 'completed', label: '已完成' },
  { key: 'cancelled', label: '已取消' },
]

const statusMap = {
  10: { label: '待接单', cls: 'status-created' },
  20: { label: '司机已接单', cls: 'status-active' },
  30: { label: '司机已到达', cls: 'status-active' },
  40: { label: '行程中', cls: 'status-active' },
  50: { label: '待支付', cls: 'status-payment' },
  60: { label: '已支付', cls: 'status-done' },
  90: { label: '已取消', cls: 'status-cancelled' },
}

const vehicleNames = { 1: '经济型', 2: '舒适型', 3: '豪华型' }

function statusLabel(s) { return statusMap[s]?.label || `状态 ${s}` }
function statusClass(s) { return statusMap[s]?.cls || '' }
function vehicleName(t) { return vehicleNames[t] || '—' }

function formatPrice(p) {
  if (!p) return '—'
  return `¥${Number(p).toFixed(2)}`
}
function formatTime(t) {
  if (!t) return '—'
  const d = new Date(t)
  return `${d.getMonth() + 1}/${d.getDate()} ${d.getHours().toString().padStart(2, '0')}:${d.getMinutes().toString().padStart(2, '0')}`
}
function canCancel(s) { return s >= 10 && s <= 30 }

function buildStatusFilter() {
  switch (activeFilter.value) {
    case 'active': return [10, 20, 30, 40]
    case 'completed': return [50, 60]
    case 'cancelled': return [90]
    default: return []
  }
}

async function loadPage(p) {
  page.value = p
  await fetchOrders()
}

async function fetchOrders() {
  if (!userStore.isLoggedIn) return
  loading.value = true
  try {
    const res = await getOrders({ page: page.value, size: pageSize, statusList: buildStatusFilter() })
    orders.value = res.records || []
    total.value = res.total || 0
  } catch {
    orders.value = []
  } finally {
    loading.value = false
  }
}

function toggleDetail(order) {
  if (expandedId.value === order.orderId) {
    expandedId.value = null
  } else {
    expandedId.value = order.orderId
  }
}

async function handleCancel(order) {
  try {
    await ElMessageBox.confirm('确认取消该订单？', '提示', { confirmButtonText: '确认取消', cancelButtonText: '暂不取消', type: 'warning' })
    await cancelOrder(order.orderId, '用户取消')
    ElMessage.success('订单已取消')
    expandedId.value = null
    await fetchOrders()
  } catch { /* user dismissed */ }
}

async function handlePay(order) {
  try {
    await payOrder(order.orderId)
    ElMessage.success('支付成功')
    expandedId.value = null
    await fetchOrders()
  } catch { /* error shown by interceptor */ }
}

watch(activeFilter, () => {
  page.value = 1
  fetchOrders()
})

watch(() => userStore.isLoggedIn, (v) => { if (v) fetchOrders() }, { immediate: true })
</script>

<style scoped>
.orders-page {
  max-width: 720px;
  margin: 0 auto;
  padding: 32px 24px;
  font-family: 'Inter', system-ui, -apple-system, sans-serif;
}
.page-heading {
  font-size: 32px;
  font-weight: 700;
  letter-spacing: -0.3px;
  margin-bottom: 24px;
  color: #000000;
}

/* ─── Filter pills — DESIGN.md: category-button ─── */
.filter-row {
  display: flex;
  gap: 8px;
  margin-bottom: 28px;
  flex-wrap: wrap;
}
.filter-pill {
  padding: 8px 18px;
  background: #efefef;
  border: none;
  border-radius: 999px;
  font-size: 14px;
  font-weight: 500;
  font-family: inherit;
  color: #5e5e5e;
  cursor: pointer;
  transition: all 0.15s;
}
.filter-pill:hover { background: #e2e2e2; color: #000000; }
.filter-pill.active { background: #000000; color: #ffffff; }

/* ─── Loading / Empty ─── */
.loading-state, .empty-state {
  text-align: center;
  padding: 64px 0;
  color: #afafaf;
}
.empty-state { display: flex; flex-direction: column; align-items: center; gap: 16px; }
.empty-text { font-size: 16px; }

.pill-btn {
  padding: 12px 28px;
  border: none;
  border-radius: 999px;
  font-size: 16px;
  font-weight: 500;
  font-family: inherit;
  cursor: pointer;
  transition: opacity 0.15s;
}
.pill-btn--black { background: #000000; color: #ffffff; }
.pill-btn--black:hover { background: #282828; }
.pill-btn--subtle { background: #efefef; color: #000000; }
.pill-btn--subtle:hover:not(:disabled) { background: #e2e2e2; }
.pill-btn:disabled { opacity: 0.4; cursor: not-allowed; }

/* ─── Order card — DESIGN.md: card-content ─── */
.order-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.order-card {
  background: #ffffff;
  border: 1px solid #e5e4e7;
  border-radius: 16px;
  padding: 20px;
  cursor: pointer;
  transition: border-color 0.15s;
}
.order-card:hover { border-color: #000000; }
.order-card.expanded { border-color: #000000; }

.card-main {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
}

/* Route */
.card-route { display: flex; gap: 12px; flex: 1; min-width: 0; }
.route-visual {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  flex-shrink: 0;
  padding-top: 4px;
}
.route-dot {
  width: 8px; height: 8px; border-radius: 50%; flex-shrink: 0;
}
.route-dot.start { background: #000000; }
.route-dot.end { background: #ffffff; border: 2px solid #000000; }
.route-line { width: 1px; flex: 1; min-height: 12px; background: #e5e4e7; }
.route-addresses {
  display: flex;
  flex-direction: column;
  gap: 8px;
  min-width: 0;
}
.addr {
  font-size: 16px;
  font-weight: 500;
  color: #000000;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.addr.end { font-weight: 400; color: #5e5e5e; }

/* Meta */
.card-meta {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 6px;
  flex-shrink: 0;
  margin-left: 16px;
}
.status-badge {
  font-size: 13px;
  font-weight: 500;
  padding: 4px 10px;
  border-radius: 999px;
}
.status-created { background: #efefef; color: #5e5e5e; }
.status-active { background: #000000; color: #ffffff; }
.status-payment { background: #efefef; color: #000000; }
.status-done { color: #5e5e5e; }
.status-cancelled { color: #afafaf; }

.price {
  font-size: 18px;
  font-weight: 700;
  color: #000000;
}

/* ─── Expanded detail ─── */
.card-detail {
  margin-top: 20px;
  padding-top: 20px;
  border-top: 1px solid #efefef;
}
.detail-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
  margin-bottom: 20px;
}
.detail-item {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.detail-label {
  font-size: 13px;
  color: #afafaf;
}
.detail-value {
  font-size: 15px;
  font-weight: 500;
  color: #000000;
}
.detail-value.mono {
  font-family: 'SF Mono', 'Consolas', monospace;
  font-size: 13px;
  overflow: hidden;
  text-overflow: ellipsis;
}
.detail-actions {
  display: flex;
  gap: 8px;
}

/* ─── Pagination ─── */
.pagination {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12px;
  padding: 24px 0;
}
.page-info {
  font-size: 14px;
  color: #5e5e5e;
}

/* ─── Responsive ─── */
@media (max-width: 768px) {
  .orders-page { padding: 16px; }
  .page-heading { font-size: 24px; }
  .card-main { flex-direction: column; gap: 12px; }
  .card-meta { flex-direction: row; align-items: center; margin-left: 0; width: 100%; justify-content: space-between; }
  .detail-grid { grid-template-columns: 1fr; }
}
</style>
