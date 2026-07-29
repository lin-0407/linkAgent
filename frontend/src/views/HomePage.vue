<script setup lang="ts">
import {
  Activity,
  ArrowRight,
  BarChart3,
  BookOpen,
  BrainCircuit,
  Clapperboard,
  FolderOpen,
  Library,
  MessageSquareText,
  PenTool,
  PlayCircle,
  ShieldCheck,
} from '@lucide/vue'

const quickEntries = [
  {
    title: '开始创作',
    description: '整理创意与素材，生成发布方案',
    route: '/creator',
    icon: PenTool,
  },
  {
    title: '检索案例',
    description: '从参考视频中查找可复用证据',
    route: '/knowledge',
    icon: Library,
  },
  {
    title: '视频复盘',
    description: '绑定公开视频并查看观众反馈',
    route: '/video-analysis',
    icon: BarChart3,
  },
  {
    title: '调用记录',
    description: '核对模型用量、耗时与失败原因',
    route: '/usage-logs',
    icon: Activity,
  },
]

const workflowSteps = [
  { index: '01', title: '确认发布方案', detail: '把创意、受众和已有材料收敛成明确方向', icon: PenTool },
  { index: '02', title: '生成制作蓝图', detail: '拆解内容结构、镜头和制作步骤', icon: Clapperboard },
  { index: '03', title: '完成成片试映', detail: '上传私有成片并执行发布前检查', icon: PlayCircle },
  { index: '04', title: '发布并绑定 BV', detail: '让公开视频与本次创作任务建立关联', icon: BookOpen },
  { index: '05', title: '分析观众反馈', detail: '从评论和弹幕中识别共鸣与偏差', icon: MessageSquareText },
  { index: '06', title: '沉淀长期偏好', detail: '把有效结论用于下一次发布决策', icon: BrainCircuit },
]

const managementEntries = [
  { title: '项目列表', detail: '继续历史发布方案与复盘', route: '/projects', icon: FolderOpen },
  { title: '记忆管理', detail: '查看系统沉淀的长期偏好', route: '/memory', icon: BrainCircuit },
  { title: '参考案例', detail: '管理案例并执行主题检索', route: '/knowledge', icon: Library },
  { title: '使用日志', detail: '查看每一次模型调用记录', route: '/usage-logs', icon: Activity },
]
</script>

<template>
  <main class="home-dashboard">
    <section class="home-overview" aria-labelledby="home-title">
      <div class="home-container home-overview-inner">
        <div class="home-intro">
          <p class="home-kicker">创作总览</p>
          <h1 id="home-title">下一条视频，从明确的发布方案开始</h1>
          <p>
            LinkAgent 把创意、制作、成片试映、公开反馈和复盘放进同一条工作流，过程与依据都可以随时回看。
          </p>
        </div>
        <div class="home-overview-actions">
          <RouterLink to="/creator" class="home-action home-action-primary">
            <PenTool :size="17" :stroke-width="1.8" aria-hidden="true" />
            开始一条创作
          </RouterLink>
          <RouterLink to="/projects" class="home-action home-action-secondary">
            <FolderOpen :size="17" :stroke-width="1.8" aria-hidden="true" />
            继续历史项目
          </RouterLink>
        </div>
      </div>
    </section>

    <div class="home-container">
      <nav class="home-quick-grid" aria-label="常用入口">
        <RouterLink
          v-for="entry in quickEntries"
          :key="entry.route"
          :to="entry.route"
          class="home-quick-entry"
        >
          <span class="home-entry-icon" aria-hidden="true">
            <component :is="entry.icon" :size="20" :stroke-width="1.7" />
          </span>
          <span class="home-entry-copy">
            <strong>{{ entry.title }}</strong>
            <small>{{ entry.description }}</small>
          </span>
          <ArrowRight class="home-entry-arrow" :size="17" :stroke-width="1.8" aria-hidden="true" />
        </RouterLink>
      </nav>

      <div class="home-workspace-grid">
        <section class="home-workflow" aria-labelledby="workflow-title">
          <header class="home-section-header">
            <div>
              <p class="home-kicker">创作流程</p>
              <h2 id="workflow-title">从发布前决策到发布后复盘</h2>
            </div>
            <RouterLink to="/creator" class="home-inline-link">
              打开创作台
              <ArrowRight :size="16" :stroke-width="1.8" aria-hidden="true" />
            </RouterLink>
          </header>

          <ol class="home-workflow-list">
            <li v-for="step in workflowSteps" :key="step.index">
              <span class="home-step-index">{{ step.index }}</span>
              <span class="home-step-icon" aria-hidden="true">
                <component :is="step.icon" :size="18" :stroke-width="1.7" />
              </span>
              <span class="home-step-copy">
                <strong>{{ step.title }}</strong>
                <small>{{ step.detail }}</small>
              </span>
            </li>
          </ol>
        </section>

        <aside class="home-management" aria-labelledby="management-title">
          <header class="home-section-header">
            <div>
              <p class="home-kicker">管理与追溯</p>
              <h2 id="management-title">常用工作区</h2>
            </div>
          </header>

          <nav class="home-management-list" aria-label="管理入口">
            <RouterLink
              v-for="entry in managementEntries"
              :key="entry.title"
              :to="entry.route"
            >
              <component :is="entry.icon" :size="18" :stroke-width="1.7" aria-hidden="true" />
              <span>
                <strong>{{ entry.title }}</strong>
                <small>{{ entry.detail }}</small>
              </span>
              <ArrowRight :size="16" :stroke-width="1.8" aria-hidden="true" />
            </RouterLink>
          </nav>

          <div class="home-private-note">
            <ShieldCheck :size="19" :stroke-width="1.7" aria-hidden="true" />
            <div>
              <strong>个人自托管</strong>
              <p>项目材料、媒体处理记录和创作偏好保存在自己的部署中。</p>
            </div>
          </div>
        </aside>
      </div>

      <section class="home-preview" aria-labelledby="preview-title">
        <header class="home-section-header">
          <div>
            <p class="home-kicker">当前工作台</p>
            <h2 id="preview-title">发布方案与创作依据在同一界面完成</h2>
          </div>
          <RouterLink to="/creator" class="home-inline-link">
            进入完整界面
            <ArrowRight :size="16" :stroke-width="1.8" aria-hidden="true" />
          </RouterLink>
        </header>
        <figure class="home-preview-figure">
          <img
            src="/screenshots/creator-publishing-plan.png"
            alt="LinkAgent 创作台界面，展示发布方案、创作者偏好和视频发布流程"
            width="1440"
            height="900"
          />
          <figcaption>创作台实际界面</figcaption>
        </figure>
      </section>
    </div>
  </main>
</template>

<style scoped>
:global(.surface-root-home) {
  padding-bottom: 0;
  background: var(--canvas);
}

.home-dashboard {
  min-height: calc(100vh - var(--surface-topbar-height));
  color: var(--text);
  background: var(--canvas);
}

.home-container {
  width: min(1280px, calc(100% - 48px));
  margin-inline: auto;
}

.home-overview {
  background: var(--surface);
  border-bottom: 1px solid var(--border);
}

.home-overview-inner {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: end;
  gap: 40px;
  padding-block: 44px 40px;
}

.home-intro {
  max-width: 760px;
}

.home-kicker {
  margin: 0 0 7px;
  color: var(--accent-strong);
  font-size: 12px;
  font-weight: var(--fw-semibold);
}

.home-intro h1 {
  margin: 0;
  color: var(--ink);
  font-size: 36px;
  font-weight: var(--fw-bold);
  letter-spacing: 0;
  line-height: 1.24;
}

.home-intro > p:last-child {
  max-width: 700px;
  margin: 14px 0 0;
  color: var(--muted);
  font-size: 15px;
  line-height: 1.75;
}

.home-overview-actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 10px;
}

.home-action {
  display: inline-flex;
  min-height: 44px;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 0 16px;
  border: 1px solid transparent;
  border-radius: var(--r-sm);
  font-size: 14px;
  font-weight: var(--fw-semibold);
  text-decoration: none;
  transition:
    color 160ms ease,
    background-color 160ms ease,
    border-color 160ms ease;
}

.home-action-primary {
  color: #fff;
  background: var(--accent);
  border-color: var(--accent);
}

.home-action-primary:hover {
  background: var(--accent-hover);
  border-color: var(--accent-hover);
}

.home-action-secondary {
  color: var(--text);
  background: var(--surface);
  border-color: var(--border-strong);
}

.home-action-secondary:hover {
  color: var(--accent-strong);
  background: var(--accent-tint);
  border-color: rgba(8, 126, 167, 0.35);
}

.home-quick-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
  padding-block: 20px 28px;
}

.home-quick-entry {
  display: grid;
  grid-template-columns: 40px minmax(0, 1fr) 18px;
  min-width: 0;
  min-height: 86px;
  align-items: center;
  gap: 11px;
  padding: 14px;
  color: var(--text);
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--r);
  text-decoration: none;
  transition:
    background-color 160ms ease,
    border-color 160ms ease;
}

.home-quick-entry:hover {
  background: #f9fcfd;
  border-color: rgba(8, 126, 167, 0.32);
}

.home-entry-icon,
.home-step-icon {
  display: inline-grid;
  place-items: center;
  color: var(--accent-strong);
  background: var(--accent-tint);
  border: 1px solid rgba(8, 126, 167, 0.16);
  border-radius: var(--r-sm);
}

.home-entry-icon {
  width: 40px;
  height: 40px;
}

.home-entry-copy,
.home-step-copy,
.home-management-list a > span {
  display: grid;
  min-width: 0;
  gap: 3px;
}

.home-entry-copy strong,
.home-step-copy strong,
.home-management-list strong {
  color: var(--ink);
  font-size: 14px;
  font-weight: var(--fw-semibold);
}

.home-entry-copy small,
.home-step-copy small,
.home-management-list small {
  overflow: hidden;
  color: var(--muted);
  font-size: 12px;
  line-height: 1.4;
  text-overflow: ellipsis;
}

.home-entry-arrow {
  color: var(--faint);
}

.home-workspace-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.5fr) minmax(300px, 0.72fr);
  border-top: 1px solid var(--border);
  border-bottom: 1px solid var(--border);
}

.home-workflow,
.home-management,
.home-preview {
  min-width: 0;
}

.home-workflow {
  padding: 30px 34px 34px 0;
  border-right: 1px solid var(--border);
}

.home-management {
  padding: 30px 0 34px 34px;
}

.home-section-header {
  display: flex;
  min-width: 0;
  align-items: end;
  justify-content: space-between;
  gap: 20px;
  margin-bottom: 18px;
}

.home-section-header h2 {
  margin: 0;
  color: var(--ink);
  font-size: 20px;
  font-weight: var(--fw-bold);
  letter-spacing: 0;
  line-height: 1.35;
}

.home-inline-link {
  display: inline-flex;
  flex: 0 0 auto;
  min-height: 36px;
  align-items: center;
  gap: 6px;
  color: var(--accent-strong);
  font-size: 13px;
  font-weight: var(--fw-semibold);
  text-decoration: none;
}

.home-inline-link:hover {
  color: var(--accent-hover);
  text-decoration: underline;
  text-underline-offset: 4px;
}

.home-workflow-list {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  margin: 0;
  padding: 0;
  list-style: none;
  border-top: 1px solid var(--border);
}

.home-workflow-list li {
  display: grid;
  grid-template-columns: 28px 36px minmax(0, 1fr);
  min-width: 0;
  min-height: 82px;
  align-items: center;
  gap: 10px;
  padding: 12px 14px 12px 0;
  border-bottom: 1px solid var(--border);
}

.home-workflow-list li:nth-child(odd) {
  padding-right: 18px;
  border-right: 1px solid var(--border);
}

.home-workflow-list li:nth-child(even) {
  padding-left: 18px;
}

.home-step-index {
  color: var(--faint);
  font-family: var(--font-code);
  font-size: 11px;
}

.home-step-icon {
  width: 36px;
  height: 36px;
}

.home-management-list {
  display: grid;
  border-top: 1px solid var(--border);
}

.home-management-list a {
  display: grid;
  grid-template-columns: 24px minmax(0, 1fr) 16px;
  min-height: 64px;
  align-items: center;
  gap: 10px;
  color: var(--muted);
  border-bottom: 1px solid var(--border);
  text-decoration: none;
}

.home-management-list a:hover {
  color: var(--accent-strong);
  background: #f9fcfd;
}

.home-private-note {
  display: grid;
  grid-template-columns: 24px minmax(0, 1fr);
  gap: 10px;
  margin-top: 20px;
  padding: 14px;
  color: var(--accent-strong);
  background: var(--accent-tint);
  border: 1px solid rgba(8, 126, 167, 0.16);
  border-radius: var(--r);
}

.home-private-note strong {
  color: var(--ink);
  font-size: 13px;
}

.home-private-note p {
  margin: 3px 0 0;
  color: var(--muted);
  font-size: 12px;
  line-height: 1.55;
}

.home-preview {
  padding-block: 34px 64px;
}

.home-preview-figure {
  position: relative;
  margin: 0;
  overflow: hidden;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--r);
}

.home-preview-figure img {
  display: block;
  width: 100%;
  height: auto;
  aspect-ratio: 16 / 9;
  object-fit: cover;
  object-position: top;
}

.home-preview-figure figcaption {
  padding: 10px 14px;
  color: var(--muted);
  background: var(--surface-sub);
  border-top: 1px solid var(--border);
  font-size: 12px;
}

@media (max-width: 1040px) {
  .home-overview-inner {
    grid-template-columns: 1fr;
    align-items: start;
    gap: 24px;
  }

  .home-overview-actions {
    justify-content: flex-start;
  }

  .home-quick-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .home-workspace-grid {
    grid-template-columns: 1fr;
  }

  .home-workflow {
    padding-right: 0;
    border-right: 0;
    border-bottom: 1px solid var(--border);
  }

  .home-management {
    padding-left: 0;
  }
}

@media (max-width: 640px) {
  .home-container {
    width: min(100% - 20px, 1280px);
  }

  .home-overview-inner {
    padding-block: 28px 30px;
  }

  .home-intro h1 {
    font-size: 28px;
  }

  .home-overview-actions,
  .home-action {
    width: 100%;
  }

  .home-quick-grid {
    grid-template-columns: 1fr;
    gap: 8px;
    padding-block: 12px 22px;
  }

  .home-quick-entry {
    min-height: 76px;
  }

  .home-workflow,
  .home-management {
    padding-block: 24px;
  }

  .home-section-header {
    align-items: start;
  }

  .home-workflow-list {
    grid-template-columns: 1fr;
  }

  .home-workflow-list li,
  .home-workflow-list li:nth-child(odd),
  .home-workflow-list li:nth-child(even) {
    min-height: 76px;
    padding: 10px 0;
    border-right: 0;
  }

  .home-preview {
    padding-block: 28px 48px;
  }
}

@media (max-width: 420px) {
  .home-section-header {
    display: grid;
    gap: 8px;
  }
}
</style>
