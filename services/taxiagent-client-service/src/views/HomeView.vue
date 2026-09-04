<template>
  <div class="home">
    <!-- Hero band — DESIGN.md: hero-band-light -->
    <section class="hero-band">
      <div class="hero-left">
        <h1 class="hero-headline">星智出行，说走就走</h1>
        <p class="hero-sub">AI 驱动的打车服务。预订、行程追踪、售后客服——一次对话全部搞定。</p>
        <div class="hero-actions">
          <button class="pill-btn pill-btn--black" @click="$router.push('/chat')">立即叫车</button>
          <button class="pill-btn pill-btn--white" @click="$router.push('/orders')">我的订单</button>
        </div>
      </div>
      <div class="hero-right">
        <svg viewBox="0 0 320 200" fill="none" xmlns="http://www.w3.org/2000/svg">
          <rect x="40" y="20" width="240" height="160" rx="16" stroke="#e5e4e7" stroke-width="1.5" />
          <circle cx="120" cy="80" r="22" stroke="#000" stroke-width="1.5" />
          <circle cx="200" cy="80" r="22" stroke="#000" stroke-width="1.5" />
          <path d="M142 80h36" stroke="#afafaf" stroke-width="1.5" stroke-dasharray="4 4" />
          <rect x="80" y="130" width="50" height="10" rx="5" fill="#efefef" />
          <rect x="190" y="130" width="50" height="10" rx="5" fill="#efefef" />
          <rect x="130" y="155" width="60" height="6" rx="3" fill="#efefef" />
        </svg>
      </div>
    </section>

    <!-- Feature cards — 2-column promo row -->
    <section class="feature-row">
      <div class="feature-card">
        <h3>AI 对话叫车</h3>
        <p>用自然语言描述行程，AI 自动解析地址、规划路线、估算价格。</p>
      </div>
      <div class="feature-card feature-card--dark">
        <h3>智能客服</h3>
        <p>订单查询、退款、发票——AI 客服 7×24 小时在线。</p>
      </div>
    </section>

    <!-- Category-like chips row -->
    <section class="chip-row">
      <div class="chip">即时打车</div>
      <div class="chip">机场接送</div>
      <div class="chip">包车服务</div>
      <div class="chip">预约用车</div>
    </section>

    <!-- Footer-like band -->
    <footer class="home-footer">
      <p>星智出行 — AI 驱动的智慧出行</p>
    </footer>
  </div>
</template>

<style scoped>
.home {
  font-family: 'Inter', system-ui, -apple-system, sans-serif;
  color: #000000;
}

/* ─── Hero band ─── */
.hero-band {
  display: flex;
  align-items: center;
  gap: 64px;
  padding: 64px 80px;
  background: #ffffff;
  max-width: 1200px;
  margin: 0 auto;
}
.hero-left {
  flex: 1;
}
.hero-headline {
  font-size: 52px;
  font-weight: 700;
  line-height: 1.23;
  letter-spacing: -0.5px;
  margin-bottom: 16px;
}
.hero-sub {
  font-size: 16px;
  line-height: 1.5;
  color: #5e5e5e;
  margin-bottom: 32px;
}
.hero-actions {
  display: flex;
  gap: 12px;
}
.hero-right {
  flex: 1;
  display: flex;
  justify-content: center;
}

/* Pill buttons — DESIGN.md */
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
.pill-btn--black {
  background: #000000;
  color: #ffffff;
}
.pill-btn--black:hover { background: #282828; }
.pill-btn--white {
  background: #ffffff;
  color: #000000;
  border: 1px solid #e5e4e7;
}
.pill-btn--white:hover { background: #f3f3f3; }

/* ─── Feature promo row — alternating band rhythm ─── */
.feature-row {
  display: flex;
  max-width: 1200px;
  margin: 0 auto;
  padding: 0 80px 48px;
  gap: 24px;
}
.feature-card {
  flex: 1;
  padding: 32px;
  border-radius: 16px;
  background: #efefef;
}
.feature-card h3 {
  font-size: 24px;
  font-weight: 700;
  margin-bottom: 12px;
  color: #000000;
}
.feature-card p {
  font-size: 16px;
  line-height: 1.5;
  color: #5e5e5e;
}
.feature-card--dark {
  background: #000000;
}
.feature-card--dark h3 {
  color: #ffffff;
}
.feature-card--dark p {
  color: #afafaf;
}

/* ─── Chip row ─── */
.chip-row {
  display: flex;
  gap: 10px;
  max-width: 1200px;
  margin: 0 auto;
  padding: 0 80px 64px;
}
.chip {
  padding: 8px 18px;
  background: #efefef;
  border-radius: 999px;
  font-size: 14px;
  font-weight: 500;
  color: #000000;
  cursor: pointer;
  transition: background 0.15s;
}
.chip:hover { background: #e2e2e2; }

/* ─── Footer band — DESIGN.md: black footer ─── */
.home-footer {
  background: #000000;
  padding: 32px 80px;
  text-align: center;
}
.home-footer p {
  font-size: 14px;
  color: #afafaf;
}

/* ─── Responsive ─── */
@media (max-width: 768px) {
  .hero-band {
    flex-direction: column;
    padding: 32px 24px;
    gap: 32px;
  }
  .hero-headline { font-size: 36px; }
  .hero-right { display: none; }
  .feature-row {
    flex-direction: column;
    padding: 0 24px 32px;
  }
  .chip-row { padding: 0 24px 32px; flex-wrap: wrap; }
  .home-footer { padding: 24px; }
}
</style>
