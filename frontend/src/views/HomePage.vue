<script setup lang="ts">
import { useRouter } from 'vue-router'

const router = useRouter()

const entries = [
  {
    index: '01',
    eyebrow: 'Pre-publish',
    title: '发布前优化',
    description: '上传视频标题、简介与文稿，AI 生成标题建议、风险点和标签方案。',
    route: '/creator',
    action: '开始优化',
  },
  {
    index: '02',
    eyebrow: 'Audience',
    title: '观众反馈分析',
    description: '导入评论弹幕数据，AI 提炼热议话题、争议点和下一期选题建议。',
    route: '/creator',
    action: '分析反馈',
  },
  {
    index: '03',
    eyebrow: 'Reference',
    title: '参考案例库',
    description: '检索 B 站标杆视频案例，获取标题套路、内容定位和观众反馈参考。',
    route: '/knowledge',
    action: '浏览案例',
  },
  {
    index: '04',
    eyebrow: 'Archive',
    title: '历史项目',
    description: '继续上次未完成的发布方案或复盘报告。',
    route: '/projects',
    action: '查看项目',
  },
]
</script>

<template>
  <main class="home-page">
    <section class="home-hero">
      <div class="home-hero-copy">
        <p class="home-kicker">linkAgent / B站创作复盘台</p>
        <h1>让每一次发视频都有依据</h1>
        <p class="home-subtitle">
          把标题、简介、文稿、评论和弹幕放到同一张工作台里，发布前看风险，发布后找下一期方向。
        </p>
        <div class="home-signal-row" aria-label="工作台能力概览">
          <span><b>4</b> 个核心流程</span>
          <span><b>AI</b> 复盘建议</span>
          <span><b>BV</b> 案例检索</span>
        </div>
      </div>

      <div class="home-console" aria-label="创作者工作台概览">
        <span class="home-console-bar"></span>
        <div>
          <small>当前工作流</small>
          <strong>发布前优化</strong>
        </div>
        <div>
          <small>关键输入</small>
          <strong>标题 / 文稿 / 弹幕</strong>
        </div>
        <div>
          <small>输出结果</small>
          <strong>建议卡片 + 复盘报告</strong>
        </div>
      </div>
    </section>

    <section class="home-entries">
      <button
        v-for="entry in entries"
        :key="entry.title"
        type="button"
        class="home-entry-card"
        @click="router.push(entry.route)"
      >
        <span class="home-entry-index">{{ entry.index }}</span>
        <small>{{ entry.eyebrow }}</small>
        <h3>{{ entry.title }}</h3>
        <p>{{ entry.description }}</p>
        <span class="home-entry-action">{{ entry.action }} →</span>
      </button>
    </section>

    <footer class="home-footer">
      <p>基于 Spring AI + DeepSeek 构建 · 本地部署 · 数据不外传</p>
    </footer>
  </main>
</template>

<style scoped>
.home-page {
  max-width: 1180px;
  margin: 0 auto;
  padding: 18px var(--s4) 26px;
}

.home-hero {
  display: grid;
  grid-template-columns: minmax(0, 1.15fr) minmax(280px, 0.85fr);
  align-items: stretch;
  gap: var(--s3);
  padding: 22px 0 14px;
}

.home-kicker {
  margin: 0 0 var(--s3);
  color: var(--accent-strong);
  font-size: 12px;
  font-weight: var(--fw-semibold);
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.home-hero h1 {
  max-width: 680px;
  margin: 0 0 10px;
  color: var(--ink);
  font-size: 34px;
  font-weight: 760;
  line-height: 1.18;
}

.home-subtitle {
  max-width: 660px;
  margin: 0;
  color: var(--muted);
  font-size: 14px;
  line-height: 1.65;
}

.home-hero-copy {
  position: relative;
  padding: 24px;
  overflow: hidden;
  background:
    linear-gradient(90deg, rgba(0, 174, 236, 0.12), transparent 42%),
    var(--surface);
  border: 1px solid rgba(0, 174, 236, 0.18);
  border-radius: var(--r);
  box-shadow: var(--sh-md);
}

.home-hero-copy::after {
  position: absolute;
  right: 18px;
  bottom: 16px;
  width: 120px;
  height: 48px;
  border-top: 4px solid var(--bili-blue);
  border-right: 4px solid var(--bili-pink);
  opacity: 0.28;
  content: '';
}

.home-signal-row {
  display: flex;
  flex-wrap: wrap;
  gap: var(--s2);
  margin-top: var(--s4);
}

.home-signal-row span {
  display: inline-flex;
  align-items: center;
  min-height: 30px;
  gap: 6px;
  padding: 0 10px;
  color: var(--text);
  background: rgba(255, 255, 255, 0.78);
  border: 1px solid var(--border);
  border-radius: var(--r-pill);
  font-size: 12px;
  font-weight: var(--fw-medium);
}

.home-signal-row b {
  color: var(--accent-strong);
}

.home-console {
  display: grid;
  align-content: start;
  gap: var(--s3);
  padding: 16px;
  background: #152237;
  border: 1px solid rgba(0, 174, 236, 0.28);
  border-radius: var(--r);
  box-shadow: var(--sh-md);
}

.home-console-bar {
  display: block;
  width: 68px;
  height: 4px;
  background: linear-gradient(90deg, var(--bili-blue), var(--bili-pink));
  border-radius: var(--r-pill);
}

.home-console div {
  display: grid;
  gap: 5px;
  padding: 11px 12px;
  background: rgba(255, 255, 255, 0.06);
  border: 1px solid rgba(255, 255, 255, 0.09);
  border-radius: var(--r-sm);
}

.home-console small {
  color: rgba(228, 239, 249, 0.62);
  font-size: 12px;
}

.home-console strong {
  color: #f8fbff;
  font-size: 14px;
  font-weight: var(--fw-semibold);
}

.home-entries {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: var(--s3);
  padding: 0 0 var(--s4);
}

.home-entry-card {
  position: relative;
  display: grid;
  min-height: 198px;
  align-content: start;
  gap: 8px;
  padding: 14px;
  overflow: hidden;
  color: inherit;
  text-align: left;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--r);
  cursor: pointer;
  box-shadow: var(--sh-sm);
  transition:
    background-color 0.16s ease,
    border-color 0.16s ease,
    box-shadow 0.16s ease,
    transform 0.16s ease;
}

.home-entry-card::before {
  position: absolute;
  inset: 0 auto 0 0;
  width: 3px;
  background: linear-gradient(180deg, var(--bili-blue), var(--bili-pink));
  opacity: 0;
  content: '';
  transition: opacity 0.16s ease;
}

.home-entry-card:hover,
.home-entry-card:focus-visible {
  border-color: rgba(0, 174, 236, 0.42);
  box-shadow:
    inset 0 -2px 0 rgba(0, 174, 236, 0.18),
    0 12px 28px rgba(23, 32, 51, 0.1);
  transform: translateY(-1px);
}

.home-entry-card:hover::before,
.home-entry-card:focus-visible::before {
  opacity: 1;
}

.home-entry-card:focus-visible {
  outline: 3px solid var(--accent-ring);
  outline-offset: 2px;
}

.home-entry-index {
  color: var(--bili-pink);
  font-family: var(--font-code);
  font-size: 12px;
  font-weight: 700;
}

.home-entry-card small {
  color: var(--muted);
  font-size: 11px;
  font-weight: var(--fw-semibold);
  letter-spacing: 0.04em;
  text-transform: uppercase;
}

.home-entry-card h3 {
  margin: 0 0 var(--s2);
  color: var(--ink);
  font-size: 16px;
  font-weight: var(--fw-semibold);
}

.home-entry-card p {
  margin: 0;
  color: var(--muted);
  font-size: 13px;
  line-height: 1.55;
}

.home-entry-action {
  align-self: end;
  margin-top: var(--s2);
  color: var(--accent);
  font-size: 13px;
  font-weight: var(--fw-semibold);
}

.home-footer {
  text-align: center;
  padding: 2px 0 var(--s3);
  color: var(--muted);
  font-size: 12px;
}

@media (max-width: 980px) {
  .home-hero,
  .home-entries {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .home-hero-copy {
    grid-column: 1 / -1;
  }
}

@media (max-width: 600px) {
  .home-page {
    padding-inline: 12px;
  }

  .home-hero,
  .home-entries {
    grid-template-columns: 1fr;
  }

  .home-hero h1 {
    font-size: 26px;
  }

  .home-hero-copy,
  .home-console {
    padding: 14px;
  }

  .home-entry-card {
    min-height: auto;
  }
}
</style>
