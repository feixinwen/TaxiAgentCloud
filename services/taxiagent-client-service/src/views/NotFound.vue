<template>
  <div class="not-found">
    <h1>404</h1>
    <p>页面不存在</p>
    <el-button @click="$router.push('/')">返回首页</el-button>
  </div>
</template>

<style scoped>
.not-found {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  height: 100vh;
  gap: 16px;
}
.not-found h1 { font-size: 72px; color: #ddd; }
.not-found p { font-size: 18px; color: #999; }
</style>
