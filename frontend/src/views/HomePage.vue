<script setup lang="ts">
import { useRouter } from 'vue-router'

const router = useRouter()

const entries = [
  {
    title: '发布前优化',
    description: '上传视频标题、简介与文稿，AI 生成标题建议、风险点和标签方案。',
    route: '/creator',
    action: '开始优化',
  },
  {
    title: '观众反馈分析',
    description: '导入评论弹幕数据，AI 提炼热议话题、争议点和下一期选题建议。',
    route: '/creator',
    action: '分析反馈',
  },
  {
    title: '参考案例库',
    description: '检索 B 站标杆视频案例，获取标题套路、内容定位和观众反馈参考。',
    route: '/knowledge',
    action: '浏览案例',
  },
  {
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
      <p class="home-kicker">linkAgent</p>
      <h1>让每一次发视频都有依据</h1>
      <p class="home-subtitle">
        帮助 B 站创作者完成发布优化、观众反馈分析与内容复盘，
        用数据和 AI 替代"凭感觉做视频"。
      </p>
    </section>

    <section class="home-entries">
      <article
        v-for="entry in entries"
        :key="entry.title"
        class="home-entry-card"
        @click="router.push(entry.route)"
      >
        <h3>{{ entry.title }}</h3>
        <p>{{ entry.description }}</p>
        <span class="home-entry-action">{{ entry.action }} →</span>
      </article>
    </section>

    <footer class="home-footer">
      <p>基于 Spring AI + DeepSeek 构建 · 本地部署 · 数据不外传</p>
    </footer>
  </main>
</template>

<style scoped>
.home-page {
  max-width: 960px;
  margin: 0 auto;
  padding: var(--s6) var(--s4);
}

.home-hero {
  text-align: center;
  padding: var(--s8) 0 var(--s6);
}

.home-kicker {
  color: var(--accent);
  font-size: 13px;
  font-weight: var(--fw-semibold);
  letter-spacing: 0.08em;
  text-transform: uppercase;
  margin-bottom: var(--s3);
}

.home-hero h1 {
  font-size: 2.2rem;
  font-weight: 750;
  line-height: 1.35;
  color: var(--ink);
  margin: 0 0 var(--s3);
}

.home-subtitle {
  color: var(--muted);
  font-size: 1.05rem;
  line-height: 1.7;
  max-width: 560px;
  margin: 0 auto;
}

.home-entries {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: var(--s4);
  padding: var(--s6) 0;
}

.home-entry-card {
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--r);
  padding: var(--s5);
  cursor: pointer;
  transition: border-color 0.15s, box-shadow 0.15s;
}

.home-entry-card:hover {
  border-color: var(--accent);
  box-shadow: 0 0 0 4px var(--accent-ring);
}

.home-entry-card h3 {
  font-size: 1.1rem;
  font-weight: var(--fw-semibold);
  color: var(--ink);
  margin: 0 0 var(--s2);
}

.home-entry-card p {
  color: var(--muted);
  font-size: 14px;
  line-height: 1.6;
  margin: 0 0 var(--s3);
}

.home-entry-action {
  color: var(--accent);
  font-size: 13px;
  font-weight: var(--fw-semibold);
}

.home-footer {
  text-align: center;
  padding: var(--s6) 0 var(--s4);
  color: var(--muted);
  font-size: 12px;
}

@media (max-width: 600px) {
  .home-entries {
    grid-template-columns: 1fr;
  }

  .home-hero h1 {
    font-size: 1.6rem;
  }
}
</style>
