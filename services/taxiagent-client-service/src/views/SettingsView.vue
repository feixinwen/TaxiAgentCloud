<template>
  <div class="settings-page">
    <h2 class="page-heading">设置</h2>

    <!-- 用户信息卡片 -->
    <section class="section">
      <h3 class="section-title">个人信息</h3>
      <div class="profile-card">
        <div class="profile-avatar">{{ initial }}</div>
        <div class="profile-body">
          <div class="profile-row">
            <span class="label">用户名</span>
            <span class="value">{{ profile.username || '—' }}</span>
          </div>
          <div class="profile-row">
            <span class="label">邮箱</span>
            <span class="value">{{ profile.email || '—' }}</span>
          </div>
          <div class="profile-row">
            <span class="label">角色</span>
            <span class="value">{{ profile.role || '—' }}</span>
          </div>
          <div class="profile-row" v-if="profile.createTime">
            <span class="label">注册时间</span>
            <span class="value">{{ formatDate(profile.createTime) }}</span>
          </div>
        </div>
      </div>
    </section>

    <!-- 常用地址 -->
    <section class="section">
      <div class="section-header">
        <h3 class="section-title">常用地点</h3>
        <button class="add-btn" @click="openPoiDialog()">+ 添加</button>
      </div>

      <div v-if="!pois.length" class="empty-hint">暂无常用地点</div>

      <div class="poi-list">
        <div v-for="poi in pois" :key="poi.id" class="poi-card">
          <div class="poi-icon">{{ tagIcon(poi.poiTag) }}</div>
          <div class="poi-body">
            <p class="poi-tag">{{ poi.poiTag || '未分类' }}</p>
            <p class="poi-name">{{ poi.poiName || '—' }}</p>
            <p class="poi-addr">{{ poi.poiAddress || '—' }}</p>
          </div>
          <div class="poi-actions">
            <button class="icon-btn" @click="openPoiDialog(poi)">编辑</button>
            <button class="icon-btn icon-btn--danger" @click="handleDeletePoi(poi)">删除</button>
          </div>
        </div>
      </div>
    </section>

    <!-- 登出 -->
    <section class="section">
      <button class="pill-btn pill-btn--subtle" @click="handleLogout">退出登录</button>
    </section>

    <!-- POI 编辑对话框 -->
    <el-dialog v-model="poiDialogOpen" :title="editingPoi ? '编辑地点' : '添加地点'" width="420px" :close-on-click-modal="false">
      <el-form label-position="top" @submit.prevent="handleSavePoi">
        <el-form-item label="分类">
          <el-select v-model="poiForm.poiTag" placeholder="请选择">
            <el-option label="家" value="家" />
            <el-option label="公司" value="公司" />
            <el-option label="其他" value="其他" />
          </el-select>
        </el-form-item>
        <el-form-item label="名称">
          <el-input v-model="poiForm.poiName" placeholder="如：上海中心大厦" />
        </el-form-item>
        <el-form-item label="地址">
          <el-input v-model="poiForm.poiAddress" placeholder="完整地址" />
        </el-form-item>
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="经度">
              <el-input v-model="poiForm.longitude" placeholder="如：121.5" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="纬度">
              <el-input v-model="poiForm.latitude" placeholder="如：31.2" />
            </el-form-item>
          </el-col>
        </el-row>
      </el-form>
      <template #footer>
        <button class="pill-btn pill-btn--subtle" @click="poiDialogOpen = false">取消</button>
        <button class="pill-btn pill-btn--black" @click="handleSavePoi" :disabled="saving">保存</button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { useUserStore } from '@/stores/user'
import { useRouter } from 'vue-router'
import { getCurrentUser, listPOIs, createPOI, updatePOI, deletePOI } from '@/api/user'
import { logoutApi } from '@/api/auth'

const router = useRouter()
const userStore = useUserStore()

const profile = ref({})
const pois = ref([])
const poiDialogOpen = ref(false)
const editingPoi = ref(null)
const saving = ref(false)
const poiForm = reactive({ poiTag: '家', poiName: '', poiAddress: '', longitude: '', latitude: '' })

const initial = computed(() => (profile.value.username || profile.value.email || '?')[0]?.toUpperCase())

function formatDate(t) {
  if (!t) return '—'
  return new Date(t).toLocaleDateString('zh-CN', { year: 'numeric', month: 'long', day: 'numeric' })
}

function tagIcon(tag) {
  if (tag === '家') return '🏠'
  if (tag === '公司') return '🏢'
  return '📍'
}

async function fetchProfile() {
  try {
    const res = await getCurrentUser()
    profile.value = res || {}
  } catch { /* handled by interceptor */ }
}

async function fetchPOIs() {
  try {
    const res = await listPOIs()
    pois.value = res || []
  } catch { pois.value = [] }
}

function openPoiDialog(poi = null) {
  editingPoi.value = poi
  if (poi) {
    poiForm.poiTag = poi.poiTag || '家'
    poiForm.poiName = poi.poiName || ''
    poiForm.poiAddress = poi.poiAddress || ''
    poiForm.longitude = poi.longitude != null ? String(poi.longitude) : ''
    poiForm.latitude = poi.latitude != null ? String(poi.latitude) : ''
  } else {
    poiForm.poiTag = '家'
    poiForm.poiName = ''
    poiForm.poiAddress = ''
    poiForm.longitude = ''
    poiForm.latitude = ''
  }
  poiDialogOpen.value = true
}

async function handleSavePoi() {
  saving.value = true
  try {
    const body = {
      poiTag: poiForm.poiTag,
      poiName: poiForm.poiName,
      poiAddress: poiForm.poiAddress,
      longitude: poiForm.longitude ? Number(poiForm.longitude) : null,
      latitude: poiForm.latitude ? Number(poiForm.latitude) : null,
    }
    if (editingPoi.value) {
      await updatePOI({ id: editingPoi.value.id, ...body })
      ElMessage.success('地点已更新')
    } else {
      await createPOI(body)
      ElMessage.success('地点已添加')
    }
    poiDialogOpen.value = false
    await fetchPOIs()
  } finally {
    saving.value = false
  }
}

async function handleDeletePoi(poi) {
  try {
    await ElMessageBox.confirm('确认删除该地点？', '提示', { confirmButtonText: '确认删除', cancelButtonText: '取消', type: 'warning' })
    await deletePOI(poi.id)
    ElMessage.success('地点已删除')
    await fetchPOIs()
  } catch { /* dismissed */ }
}

async function handleLogout() {
  try { await logoutApi() } catch { /* 会话可能已失效 */ }
  userStore.logout()
  router.push('/auth')
}

onMounted(() => {
  fetchProfile()
  fetchPOIs()
})
</script>

<style scoped>
.settings-page {
  max-width: 640px;
  margin: 0 auto;
  padding: 32px 24px;
  font-family: 'Inter', system-ui, -apple-system, sans-serif;
}
.page-heading {
  font-size: 32px;
  font-weight: 700;
  letter-spacing: -0.3px;
  margin-bottom: 32px;
  color: #000000;
}

/* ─── Sections ─── */
.section {
  margin-bottom: 36px;
}
.section-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}
.section-title {
  font-size: 18px;
  font-weight: 700;
  color: #000000;
}

/* ─── Profile card ─── */
.profile-card {
  display: flex;
  gap: 20px;
  background: #ffffff;
  border: 1px solid #e5e4e7;
  border-radius: 16px;
  padding: 24px;
}
.profile-avatar {
  width: 56px;
  height: 56px;
  border-radius: 50%;
  background: #000000;
  color: #ffffff;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 22px;
  font-weight: 700;
  flex-shrink: 0;
}
.profile-body {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.profile-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.profile-row .label {
  font-size: 14px;
  color: #5e5e5e;
}
.profile-row .value {
  font-size: 15px;
  font-weight: 500;
  color: #000000;
}

/* ─── POI cards ─── */
.empty-hint {
  font-size: 14px;
  color: #afafaf;
  padding: 16px 0;
}
.poi-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.poi-card {
  display: flex;
  align-items: center;
  gap: 14px;
  background: #ffffff;
  border: 1px solid #e5e4e7;
  border-radius: 16px;
  padding: 16px 20px;
}
.poi-icon { font-size: 22px; flex-shrink: 0; }
.poi-body { flex: 1; min-width: 0; }
.poi-tag { font-size: 13px; font-weight: 500; color: #afafaf; }
.poi-name { font-size: 16px; font-weight: 500; color: #000000; }
.poi-addr {
  font-size: 14px;
  color: #5e5e5e;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.poi-actions {
  display: flex;
  gap: 8px;
  flex-shrink: 0;
}
.icon-btn {
  background: none;
  border: none;
  font-size: 14px;
  font-weight: 500;
  color: #5e5e5e;
  cursor: pointer;
  font-family: inherit;
  padding: 4px 8px;
  border-radius: 999px;
  transition: all 0.15s;
}
.icon-btn:hover { background: #efefef; color: #000000; }
.icon-btn--danger:hover { color: #000000; }

.add-btn {
  background: none;
  border: none;
  font-size: 15px;
  font-weight: 500;
  color: #000000;
  cursor: pointer;
  font-family: inherit;
  padding: 6px 14px;
  border-radius: 999px;
  transition: background 0.15s;
}
.add-btn:hover { background: #efefef; }

/* ─── Buttons ─── */
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
.pill-btn--subtle { background: #efefef; color: #000000; }
.pill-btn--subtle:hover { background: #e2e2e2; }
.pill-btn--black { background: #000000; color: #ffffff; }
.pill-btn--black:hover:not(:disabled) { background: #282828; }
.pill-btn:disabled { opacity: 0.4; cursor: not-allowed; }
</style>
