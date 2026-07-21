<script setup lang="ts">
const workflowSteps = [
  {
    index: '01',
    stage: 'IDEA',
    title: '创意输入',
    description: '从一个选题想法开始，补齐受众、表达方向和已有材料。',
  },
  {
    index: '02',
    stage: 'PLAN',
    title: '发布方案',
    description: '结合创作者偏好与案例证据，生成标题、简介、标签和风险建议。',
  },
  {
    index: '03',
    stage: 'PREVIEW',
    title: '成片试映',
    description: '方案确认后上传成片，先完成私有媒体校验和发布前检查。',
  },
  {
    index: '04',
    stage: 'PUBLISH',
    title: '公开视频绑定',
    description: '正式发布后绑定对应 BV，让创作任务与真实作品建立关联。',
  },
  {
    index: '05',
    stage: 'INSIGHT',
    title: '反馈分析',
    description: '聚合评论与弹幕证据，识别观众共鸣、争议和理解偏差。',
  },
  {
    index: '06',
    stage: 'MEMORY',
    title: '复盘沉淀',
    description: '把有效结论写入创作者偏好，让下一次发布不再从零开始。',
  },
]

const capabilityCards = [
  {
    key: 'strategy',
    eyebrow: 'AI PUBLISHING PLAN',
    title: '把零散想法，变成可执行的发布方案',
    description:
      'Agent 同时理解任务材料、历史偏好、视频语境和案例证据，输出的不只是文案，还有选择理由与风险边界。',
    route: '/creator',
    action: '生成发布方案',
    iconPaths: ['M5 4.5h14v15H5z', 'M8 9h8M8 13h5M8 16.5h7', 'M16.5 3v4M18.5 5h-4'],
  },
  {
    key: 'knowledge',
    eyebrow: 'EVIDENCE LIBRARY',
    title: '案例不是收藏，是决策证据',
    description: '从 B 站参考案例中检索标题打法、内容定位与观众反馈，为本期建议提供可回看的依据。',
    route: '/knowledge',
    action: '浏览参考案例',
    iconPaths: [
      'M4.5 5.5h6.5a3 3 0 013 3v10a3 3 0 00-3-3H4.5z',
      'M19.5 5.5H13a3 3 0 00-3 3v10a3 3 0 013-3h6.5z',
    ],
  },
  {
    key: 'media',
    eyebrow: 'PRIVATE MEDIA',
    title: '先试映成片，再进入发布后分析',
    description:
      'MP4 分片直传私有对象存储，支持断点恢复与媒体探测，避免发布方案确认后直接跳过成片检查。',
    route: '/creator',
    action: '进入成片试映',
    iconPaths: ['M4 6h16v12H4z', 'M9.5 9l5 3-5 3z', 'M7 21h10'],
  },
  {
    key: 'feedback',
    eyebrow: 'AUDIENCE LOOP',
    title: '让真实反馈，回到下一次创作',
    description:
      '将公开视频与创作任务关联，从评论弹幕中提炼共鸣、争议和选题机会，再沉淀为长期创作偏好。',
    route: '/video-analysis',
    action: '查看视频分析',
    iconPaths: ['M4.5 5.5h15v10h-8l-4 3v-3h-3z', 'M8 9.5h8M8 12.5h5'],
  },
]

const architectureNodes = [
  { label: '创作者输入', detail: '创意 / 文稿 / 成片', tone: 'source' },
  { label: '工作流编排', detail: 'Vue 3 + SSE', tone: 'flow' },
  { label: 'Agent 推理', detail: 'Spring AI + DeepSeek', tone: 'agent' },
  { label: '私有资产', detail: 'MySQL / Milvus / OSS', tone: 'data' },
]
</script>

<template>
  <main class="home-page">
    <section class="home-hero" aria-labelledby="home-title">
      <div class="home-hero-grid" aria-hidden="true"></div>

      <div class="home-hero-copy">
        <p class="home-eyebrow">
          <span aria-hidden="true"></span>
          为 B 站创作者打造的个人 AI 工作台
        </p>
        <h1 id="home-title">
          从创意到发布，
          <span>每一步都有依据</span>
        </h1>
        <p class="home-hero-description">
          把创意输入、发布方案、成片试映、观众反馈和复盘沉淀到同一条工作流。AI
          不只生成内容，也保留过程、证据和你的创作偏好。
        </p>

        <div class="home-hero-actions">
          <RouterLink to="/creator" class="home-primary-action">
            开始一条创作
            <svg viewBox="0 0 20 20" aria-hidden="true">
              <path d="M4 10h11M11 6l4 4-4 4" />
            </svg>
          </RouterLink>
          <RouterLink to="/projects" class="home-secondary-action"> 继续历史项目 </RouterLink>
        </div>

        <ul class="home-trust-list" aria-label="产品特性">
          <li>个人自托管</li>
          <li>私有媒体直传</li>
          <li>Agent 过程可追溯</li>
        </ul>
      </div>

      <div class="home-product-stage" aria-label="LinkAgent 产品能力预览">
        <div class="home-product-note home-product-note-top">
          <span aria-hidden="true"></span>
          Agent 协作中
        </div>

        <div class="home-dashboard">
          <header class="home-dashboard-bar">
            <div class="home-window-controls" aria-hidden="true"><i></i><i></i><i></i></div>
            <span>LinkAgent / 创作台</span>
            <b><i aria-hidden="true"></i> 已连接</b>
          </header>

          <div class="home-dashboard-body">
            <aside class="home-dashboard-sidebar">
              <div class="home-preview-brand">
                <span aria-hidden="true"></span>
                <div>
                  <strong>本期创作</strong>
                  <small>AI 工具实测</small>
                </div>
              </div>
              <ol>
                <li class="completed"><b>1</b><span>创作材料</span></li>
                <li class="active"><b>2</b><span>发布方案</span></li>
                <li><b>3</b><span>成片试映</span></li>
                <li><b>4</b><span>观众反馈</span></li>
                <li><b>5</b><span>复盘报告</span></li>
              </ol>
              <div class="home-sidebar-memory">
                <span>创作者记忆</span>
                <strong>已载入历史偏好</strong>
              </div>
            </aside>

            <section class="home-dashboard-content">
              <header class="home-preview-header">
                <div>
                  <span>发布方案</span>
                  <h2>让标题承接内容价值</h2>
                </div>
                <b>方案已就绪</b>
              </header>

              <div class="home-context-chips" aria-label="已载入上下文">
                <span>科技区</span>
                <span>硬核实测</span>
                <span>沿用历史偏好</span>
              </div>

              <div class="home-preview-grid">
                <div class="home-plan-panel">
                  <div class="home-panel-heading">
                    <div>
                      <small>标题候选</small>
                      <strong>基于内容卖点生成</strong>
                    </div>
                    <span>3 个方案</span>
                  </div>

                  <div class="home-title-candidate featured">
                    <div><b>主方案</b><span>平衡点击与信任</span></div>
                    <p>我把 AI Agent 用进创作流程后，真正省下了什么？</p>
                  </div>
                  <div class="home-title-candidate">
                    <div><b>叙事向</b><span>突出真实体验</span></div>
                    <p>连续使用 30 天，我重新理解了 AI 创作工具</p>
                  </div>
                  <div class="home-title-candidate muted">
                    <div><b>搜索向</b><span>强化主题词</span></div>
                    <p>AI Agent 创作工作流：从选题到复盘的完整实测</p>
                  </div>
                </div>

                <aside class="home-review-panel">
                  <div class="home-review-icon" aria-hidden="true">
                    <svg viewBox="0 0 24 24">
                      <path d="M12 3l7 3v5c0 4.5-2.8 8-7 10-4.2-2-7-5.5-7-10V6z" />
                      <path d="M8.5 12l2.2 2.2 4.8-5" />
                    </svg>
                  </div>
                  <span>AI 交叉审查</span>
                  <strong>表达可信，可进入确认</strong>
                  <ul>
                    <li>核心卖点已覆盖</li>
                    <li>标题与文稿一致</li>
                    <li>夸张风险较低</li>
                  </ul>
                </aside>
              </div>

              <footer class="home-evidence-bar">
                <span><i aria-hidden="true"></i> 已关联 4 条案例证据</span>
                <b>查看推理过程</b>
              </footer>
            </section>
          </div>
        </div>

        <div class="home-product-note home-product-note-bottom">
          <span>下一步</span>
          上传成片试映
          <svg viewBox="0 0 20 20" aria-hidden="true"><path d="M4 10h11M11 6l4 4-4 4" /></svg>
        </div>
      </div>
    </section>

    <dl class="home-proof-strip" aria-label="LinkAgent 核心能力">
      <div>
        <dt>完整创作闭环</dt>
        <dd>发布前到发布后</dd>
      </div>
      <div>
        <dt>私有媒体链路</dt>
        <dd>成片分片直传 OSS</dd>
      </div>
      <div>
        <dt>Agent 可追溯</dt>
        <dd>步骤、证据与用量可回看</dd>
      </div>
      <div>
        <dt>长期偏好记忆</dt>
        <dd>每次复盘服务下一次创作</dd>
      </div>
    </dl>

    <section class="home-section home-workflow" aria-labelledby="workflow-title">
      <header class="home-section-heading">
        <div>
          <p class="home-section-kicker"><span>01</span> CREATOR WORKFLOW</p>
          <h2 id="workflow-title">不是一次生成，<br />是一条创作闭环</h2>
        </div>
        <p>
          LinkAgent
          把发布前决策和发布后反馈接在一起。每个阶段都承接上一步的真实材料，不让复盘停在一份孤立报告里。
        </p>
      </header>

      <ol class="home-workflow-list">
        <li v-for="step in workflowSteps" :key="step.index">
          <div class="home-workflow-index">
            <span>{{ step.index }}</span>
            <i aria-hidden="true"></i>
          </div>
          <small>{{ step.stage }}</small>
          <h3>{{ step.title }}</h3>
          <p>{{ step.description }}</p>
        </li>
      </ol>
    </section>

    <section class="home-capability-stage" aria-labelledby="capability-title">
      <div class="home-section home-capabilities">
        <header class="home-section-heading home-section-heading-light">
          <div>
            <p class="home-section-kicker"><span>02</span> ONE CREATOR OS</p>
            <h2 id="capability-title">创作前后，<br />都在同一个上下文里</h2>
          </div>
          <p>
            不是把几个 AI 工具拼在一起，而是围绕同一条视频任务保存材料、决策、成片和反馈，让 Agent
            真正理解“这一期”发生了什么。
          </p>
        </header>

        <div class="home-capability-grid">
          <article
            v-for="capability in capabilityCards"
            :key="capability.key"
            class="home-capability-card"
            :class="`is-${capability.key}`"
          >
            <div class="home-capability-copy">
              <div class="home-capability-icon" aria-hidden="true">
                <svg viewBox="0 0 24 24">
                  <path v-for="path in capability.iconPaths" :key="path" :d="path" />
                </svg>
              </div>
              <p>{{ capability.eyebrow }}</p>
              <h3>{{ capability.title }}</h3>
              <span>{{ capability.description }}</span>
            </div>

            <div
              v-if="capability.key === 'strategy'"
              class="home-capability-visual home-strategy-visual"
              aria-hidden="true"
            >
              <div class="home-strategy-head">
                <span>发布决策</span>
                <b>Agent generated</b>
              </div>
              <div class="home-strategy-row active">
                <i></i><span>目标受众与核心卖点</span><b>已提炼</b>
              </div>
              <div class="home-strategy-row"><i></i><span>标题与简介候选</span><b>3 组</b></div>
              <div class="home-strategy-row"><i></i><span>风险点与修改计划</span><b>可执行</b></div>
              <div class="home-strategy-trace"><span></span>案例证据与创作者偏好已参与推理</div>
            </div>

            <div
              v-else-if="capability.key === 'knowledge'"
              class="home-capability-visual home-knowledge-visual"
              aria-hidden="true"
            >
              <div class="home-search-pill">
                <svg viewBox="0 0 20 20">
                  <circle cx="8.5" cy="8.5" r="4.5" />
                  <path d="M12 12l4 4" />
                </svg>
                <span>AI Agent 创作工作流</span>
              </div>
              <div class="home-case-card">
                <b>案例证据 01</b>
                <span>标题先给结果，再补过程</span>
                <i></i>
              </div>
              <div class="home-case-card offset">
                <b>案例证据 02</b>
                <span>评论更关注真实体验</span>
                <i></i>
              </div>
            </div>

            <div
              v-else-if="capability.key === 'media'"
              class="home-capability-visual home-media-visual"
              aria-hidden="true"
            >
              <div class="home-media-file">
                <div class="home-media-play"><span></span></div>
                <div><strong>final-v03.mp4</strong><small>私有成片 · MP4</small></div>
                <b>86%</b>
              </div>
              <div class="home-media-progress"><span></span></div>
              <div class="home-media-meta">
                <span>分片直传</span><span>断点恢复</span><span>媒体探测</span>
              </div>
            </div>

            <div v-else class="home-capability-visual home-feedback-visual" aria-hidden="true">
              <div class="home-feedback-summary">
                <span>观众反馈摘要</span>
                <strong>“实测过程”是本期最强共鸣点</strong>
              </div>
              <div class="home-feedback-tags">
                <span>真实感</span><span>工具选择</span><span>流程细节</span>
              </div>
              <div class="home-feedback-bars">
                <i style="--bar-width: 82%"></i>
                <i style="--bar-width: 64%"></i>
                <i style="--bar-width: 43%"></i>
              </div>
            </div>

            <RouterLink :to="capability.route" class="home-capability-link">
              {{ capability.action }}
              <svg viewBox="0 0 20 20" aria-hidden="true"><path d="M4 10h11M11 6l4 4-4 4" /></svg>
            </RouterLink>
          </article>
        </div>
      </div>
    </section>

    <section class="home-section home-ownership" aria-labelledby="ownership-title">
      <div class="home-ownership-copy">
        <p class="home-section-kicker"><span>03</span> SELF-HOSTED BY DESIGN</p>
        <h2 id="ownership-title">你的创作资产，<br />由你的工作台管理</h2>
        <p>
          LinkAgent
          面向个人自托管场景设计。项目材料、工作流记录和复盘资产保存在你的部署中；成片由浏览器分片直传私有对象存储，业务服务不转发大文件。
        </p>
        <ul>
          <li>单人工作台，不强加注册、租户和复杂权限体系</li>
          <li>模型、向量库和数据服务按自己的环境配置</li>
          <li>开发者模式下可查看 Agent 步骤、证据与调用开销</li>
        </ul>
        <RouterLink to="/creator" class="home-inline-action">
          打开我的创作台
          <svg viewBox="0 0 20 20" aria-hidden="true"><path d="M4 10h11M11 6l4 4-4 4" /></svg>
        </RouterLink>
      </div>

      <div class="home-architecture-card" aria-label="LinkAgent 自托管架构概览">
        <header>
          <div><i aria-hidden="true"></i><span>linkagent.local</span></div>
          <b>由你控制</b>
        </header>
        <div class="home-architecture-map">
          <template v-for="(node, index) in architectureNodes" :key="node.label">
            <div class="home-architecture-node" :class="`is-${node.tone}`">
              <span>{{ node.label }}</span>
              <strong>{{ node.detail }}</strong>
            </div>
            <svg v-if="index < architectureNodes.length - 1" viewBox="0 0 28 20" aria-hidden="true">
              <path d="M2 10h22M19 5l5 5-5 5" />
            </svg>
          </template>
        </div>
        <div class="home-stack-list" aria-label="技术栈">
          <span>Spring AI</span>
          <span>DeepSeek</span>
          <span>Milvus</span>
          <span>Redis</span>
          <span>MySQL</span>
          <span>Vue 3 + SSE</span>
        </div>
        <footer>
          <span><i aria-hidden="true"></i> 服务状态可观测</span>
          <span>Open source · Self-hosted</span>
        </footer>
      </div>
    </section>

    <section class="home-final-cta" aria-labelledby="final-cta-title">
      <p>YOUR NEXT VIDEO STARTS HERE</p>
      <h2 id="final-cta-title">下一条视频，从有依据的决策开始</h2>
      <span>不必先准备完整文稿，一个选题想法就能开始。</span>
      <div>
        <RouterLink to="/creator" class="home-primary-action">
          进入 LinkAgent
          <svg viewBox="0 0 20 20" aria-hidden="true"><path d="M4 10h11M11 6l4 4-4 4" /></svg>
        </RouterLink>
        <RouterLink to="/knowledge" class="home-secondary-action home-secondary-action-dark">
          先看看参考案例
        </RouterLink>
      </div>
    </section>

    <footer class="home-footer">
      <RouterLink to="/" class="home-footer-brand">
        <span aria-hidden="true"></span>
        <strong>LinkAgent</strong>
      </RouterLink>
      <p>为 B 站创作者构建的开源个人 AI 工作台</p>
      <nav aria-label="页脚导航">
        <RouterLink to="/creator">创作台</RouterLink>
        <RouterLink to="/knowledge">参考案例</RouterLink>
        <RouterLink to="/projects">项目列表</RouterLink>
        <RouterLink to="/memory">记忆管理</RouterLink>
      </nav>
    </footer>
  </main>
</template>

<style scoped>
:global(.surface-root-home) {
  background: #f4f8fb;
}

:global(.surface-topbar-home) {
  background: rgba(248, 251, 253, 0.9);
  border-bottom-color: rgba(16, 31, 48, 0.08);
  box-shadow: none;
}

:global(.surface-topbar-home .surface-switch) {
  min-width: 0;
  background: rgba(255, 255, 255, 0.7);
  border-color: rgba(16, 31, 48, 0.08);
  box-shadow: none;
}

:global(.surface-topbar-home .surface-switch button.active) {
  color: #071827;
  background: #fff;
  border-color: rgba(16, 31, 48, 0.1);
  box-shadow: 0 4px 12px rgba(16, 31, 48, 0.08);
}

.home-page {
  --home-cyan: #19c3f1;
  --home-cyan-deep: #009fd5;
  --home-paper: #f4f8fb;
  position: relative;
  z-index: 1;
  min-height: 100vh;
  overflow: hidden;
  color: #26394a;
  background: var(--home-paper);
}

.home-hero {
  position: relative;
  display: grid;
  width: 100%;
  min-height: 680px;
  grid-template-columns: minmax(410px, 0.86fr) minmax(560px, 1.14fr);
  align-items: center;
  gap: clamp(34px, 5vw, 76px);
  margin: 0;
  padding: 64px max(24px, calc((100vw - 1260px) / 2));
  overflow: hidden;
  color: #eaf4fa;
  background: #071522;
  border-block: 1px solid rgba(169, 221, 238, 0.13);
  box-shadow: 0 24px 64px rgba(4, 18, 30, 0.16);
}

.home-hero-grid {
  position: absolute;
  inset: 0;
  opacity: 0.24;
  background-image:
    linear-gradient(rgba(118, 196, 220, 0.08) 1px, transparent 1px),
    linear-gradient(90deg, rgba(118, 196, 220, 0.08) 1px, transparent 1px);
  background-size: 46px 46px;
  mask-image: linear-gradient(90deg, #000 0%, transparent 68%);
}

.home-hero-copy,
.home-product-stage {
  position: relative;
  z-index: 2;
  min-width: 0;
}

.home-hero-copy {
  max-width: 620px;
}

.home-eyebrow {
  display: inline-flex;
  align-items: center;
  min-height: 34px;
  gap: 9px;
  margin: 0 0 24px;
  padding: 0 13px;
  color: #c5e8f4;
  background: rgba(255, 255, 255, 0.06);
  border: 1px solid rgba(149, 221, 242, 0.17);
  border-radius: 999px;
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0;
}

.home-eyebrow span {
  width: 7px;
  height: 7px;
  background: var(--home-cyan);
  border-radius: 50%;
  box-shadow: 0 0 0 5px rgba(25, 195, 241, 0.12);
}

.home-hero h1 {
  max-width: 650px;
  margin: 0;
  color: #f7fbfd;
  font-family: 'Segoe UI Variable Display', 'Microsoft YaHei UI', sans-serif;
  font-size: 60px;
  font-weight: 730;
  letter-spacing: 0;
  line-height: 1.1;
}

.home-hero h1 span {
  display: block;
  color: var(--home-cyan);
}

.home-hero-description {
  max-width: 580px;
  margin: 25px 0 0;
  color: rgba(222, 238, 246, 0.72);
  font-size: 16px;
  line-height: 1.85;
}

.home-hero-actions,
.home-final-cta > div:last-child {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
  margin-top: 32px;
}

.home-primary-action,
.home-secondary-action {
  display: inline-flex;
  min-height: 48px;
  align-items: center;
  justify-content: center;
  gap: 10px;
  padding: 0 20px;
  border-radius: 8px;
  font-size: 14px;
  font-weight: 700;
  text-decoration: none;
  transition:
    color 180ms ease,
    background-color 180ms ease,
    border-color 180ms ease,
    box-shadow 180ms ease,
    transform 180ms ease;
}

.home-primary-action {
  color: #042133;
  background: var(--home-cyan);
  border: 1px solid var(--home-cyan);
  box-shadow: 0 12px 26px rgba(25, 195, 241, 0.2);
}

.home-primary-action:hover {
  background: #54d8f7;
  border-color: #54d8f7;
  box-shadow: 0 16px 32px rgba(25, 195, 241, 0.28);
  transform: translateY(-2px);
}

.home-secondary-action {
  color: #e4f1f6;
  background: rgba(255, 255, 255, 0.05);
  border: 1px solid rgba(199, 228, 238, 0.18);
}

.home-secondary-action:hover {
  color: #fff;
  background: rgba(255, 255, 255, 0.1);
  border-color: rgba(199, 228, 238, 0.34);
  transform: translateY(-2px);
}

.home-primary-action svg,
.home-capability-link svg,
.home-inline-action svg,
.home-product-note svg {
  width: 18px;
  height: 18px;
  fill: none;
  stroke: currentColor;
  stroke-linecap: round;
  stroke-linejoin: round;
  stroke-width: 1.8;
}

.home-trust-list {
  display: flex;
  flex-wrap: wrap;
  gap: 10px 18px;
  margin: 28px 0 0;
  padding: 0;
  color: rgba(207, 229, 237, 0.62);
  font-size: 12px;
  list-style: none;
}

.home-trust-list li {
  display: inline-flex;
  align-items: center;
  gap: 7px;
}

.home-trust-list li::before {
  width: 12px;
  height: 12px;
  background:
    linear-gradient(135deg, transparent 45%, var(--home-cyan) 46% 56%, transparent 57%) 1px 1px /
      7px 7px no-repeat,
    linear-gradient(45deg, transparent 45%, var(--home-cyan) 46% 56%, transparent 57%) 5px 0 / 7px
      10px no-repeat;
  content: '';
}

.home-product-stage {
  width: 100%;
  padding: 34px 14px 28px 0;
}

.home-product-stage::before {
  content: none;
}

.home-dashboard {
  position: relative;
  z-index: 1;
  overflow: hidden;
  background: #f7fafc;
  border: 1px solid rgba(199, 229, 239, 0.28);
  border-radius: 8px;
  box-shadow:
    0 42px 70px rgba(0, 5, 12, 0.38),
    0 0 0 7px rgba(255, 255, 255, 0.025);
  transform: perspective(1400px) rotateY(-2deg) rotateX(0.8deg);
  transform-origin: center left;
}

.home-dashboard-bar {
  display: grid;
  min-height: 43px;
  grid-template-columns: 1fr auto 1fr;
  align-items: center;
  padding: 0 15px;
  color: #647789;
  background: #fff;
  border-bottom: 1px solid #e7edf2;
  font-size: 10px;
}

.home-window-controls {
  display: flex;
  gap: 5px;
}

.home-window-controls i {
  width: 7px;
  height: 7px;
  background: #d7e0e7;
  border-radius: 50%;
}

.home-window-controls i:first-child {
  background: #fb7299;
}

.home-window-controls i:nth-child(2) {
  background: #f1c65b;
}

.home-window-controls i:last-child {
  background: #55c991;
}

.home-dashboard-bar > span {
  font-weight: 600;
}

.home-dashboard-bar > b {
  display: inline-flex;
  justify-self: end;
  align-items: center;
  gap: 5px;
  color: #3d7c60;
  font-weight: 600;
}

.home-dashboard-bar > b i {
  width: 6px;
  height: 6px;
  background: #45c78a;
  border-radius: 50%;
  box-shadow: 0 0 0 3px rgba(69, 199, 138, 0.12);
}

.home-dashboard-body {
  display: grid;
  min-height: 440px;
  grid-template-columns: 146px minmax(0, 1fr);
}

.home-dashboard-sidebar {
  display: flex;
  flex-direction: column;
  min-width: 0;
  padding: 18px 12px 14px;
  color: #526576;
  background: #eef4f7;
  border-right: 1px solid #e0e9ee;
}

.home-preview-brand {
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 0 5px 16px;
  border-bottom: 1px solid #dde7ec;
}

.home-preview-brand > span {
  position: relative;
  flex: 0 0 auto;
  width: 28px;
  height: 28px;
  background: var(--home-cyan-deep);
  border-radius: 8px;
}

.home-preview-brand > span::after {
  position: absolute;
  top: 8px;
  left: 10px;
  width: 0;
  height: 0;
  border-top: 6px solid transparent;
  border-bottom: 6px solid transparent;
  border-left: 8px solid #fff;
  content: '';
}

.home-preview-brand div {
  display: grid;
  min-width: 0;
  gap: 2px;
}

.home-preview-brand strong,
.home-preview-brand small {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.home-preview-brand strong {
  color: #173247;
  font-size: 10px;
}

.home-preview-brand small {
  color: #8494a1;
  font-size: 8px;
}

.home-dashboard-sidebar ol {
  display: grid;
  gap: 5px;
  margin: 15px 0 0;
  padding: 0;
  list-style: none;
}

.home-dashboard-sidebar li {
  display: grid;
  min-height: 38px;
  grid-template-columns: 21px minmax(0, 1fr);
  align-items: center;
  gap: 7px;
  padding: 0 7px;
  border: 1px solid transparent;
  border-radius: 7px;
  font-size: 9px;
  font-weight: 600;
}

.home-dashboard-sidebar li b {
  display: grid;
  width: 19px;
  height: 19px;
  place-items: center;
  color: #8797a4;
  background: #fff;
  border: 1px solid #dbe5ea;
  border-radius: 6px;
  font-size: 8px;
}

.home-dashboard-sidebar li.completed b {
  color: #338460;
  background: #e6f7ef;
  border-color: #bee9d5;
}

.home-dashboard-sidebar li.active {
  color: #0b6b91;
  background: #fff;
  border-color: #dce9ef;
  box-shadow: 0 5px 15px rgba(28, 67, 87, 0.06);
}

.home-dashboard-sidebar li.active b {
  color: #fff;
  background: var(--home-cyan-deep);
  border-color: var(--home-cyan-deep);
}

.home-sidebar-memory {
  display: grid;
  gap: 4px;
  margin-top: auto;
  padding: 10px;
  background: rgba(255, 255, 255, 0.7);
  border: 1px solid #dce8ed;
  border-radius: 8px;
}

.home-sidebar-memory span {
  color: #83929f;
  font-size: 8px;
}

.home-sidebar-memory strong {
  color: #34566c;
  font-size: 8px;
}

.home-dashboard-content {
  display: flex;
  min-width: 0;
  flex-direction: column;
  padding: 23px 22px 17px;
  color: #294052;
}

.home-preview-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 14px;
}

.home-preview-header > div {
  display: grid;
  gap: 3px;
}

.home-preview-header span {
  color: #8797a4;
  font-size: 9px;
  font-weight: 600;
}

.home-preview-header h2 {
  margin: 0;
  color: #102d42;
  font-size: 17px;
  font-weight: 700;
}

.home-preview-header > b {
  padding: 5px 8px;
  color: #177195;
  background: #e7f8fc;
  border: 1px solid #bdebf5;
  border-radius: 999px;
  font-size: 8px;
  white-space: nowrap;
}

.home-context-chips,
.home-media-meta,
.home-feedback-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
}

.home-context-chips {
  margin-top: 13px;
}

.home-context-chips span {
  padding: 4px 7px;
  color: #667988;
  background: #fff;
  border: 1px solid #e0e9ee;
  border-radius: 999px;
  font-size: 8px;
}

.home-preview-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 148px;
  gap: 11px;
  margin-top: 13px;
}

.home-plan-panel,
.home-review-panel {
  background: #fff;
  border: 1px solid #e1e9ee;
  border-radius: 8px;
}

.home-plan-panel {
  padding: 12px;
}

.home-panel-heading,
.home-title-candidate > div,
.home-strategy-head,
.home-strategy-row,
.home-media-file,
.home-feedback-summary {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.home-panel-heading > div {
  display: grid;
  gap: 2px;
}

.home-panel-heading small,
.home-title-candidate span {
  color: #91a0aa;
  font-size: 7px;
}

.home-panel-heading strong {
  color: #28465a;
  font-size: 9px;
}

.home-panel-heading > span {
  color: #7290a2;
  font-size: 8px;
}

.home-title-candidate {
  display: grid;
  gap: 6px;
  margin-top: 8px;
  padding: 9px 10px;
  background: #f8fafb;
  border: 1px solid #e7edf1;
  border-radius: 8px;
}

.home-title-candidate.featured {
  background: #f0fbfe;
  border-color: #aee8f5;
  box-shadow: inset 3px 0 0 var(--home-cyan);
}

.home-title-candidate.muted {
  opacity: 0.72;
}

.home-title-candidate b {
  color: #19799c;
  font-size: 8px;
}

.home-title-candidate p {
  margin: 0;
  color: #294052;
  font-size: 9px;
  font-weight: 600;
  line-height: 1.45;
}

.home-review-panel {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  padding: 13px;
  background: #102f42;
  border-color: #1d4257;
}

.home-review-icon {
  display: grid;
  width: 30px;
  height: 30px;
  place-items: center;
  color: var(--home-cyan);
  background: rgba(25, 195, 241, 0.1);
  border: 1px solid rgba(25, 195, 241, 0.2);
  border-radius: 8px;
}

.home-review-icon svg {
  width: 18px;
  height: 18px;
  fill: none;
  stroke: currentColor;
  stroke-linecap: round;
  stroke-linejoin: round;
  stroke-width: 1.6;
}

.home-review-panel > span {
  margin-top: 13px;
  color: #77d7ef;
  font-size: 8px;
  font-weight: 600;
}

.home-review-panel > strong {
  margin-top: 4px;
  color: #f3fbfd;
  font-size: 10px;
  line-height: 1.5;
}

.home-review-panel ul {
  display: grid;
  gap: 7px;
  margin: 13px 0 0;
  padding: 0;
  color: #acc5d0;
  font-size: 8px;
  list-style: none;
}

.home-review-panel li {
  display: flex;
  align-items: center;
  gap: 6px;
}

.home-review-panel li::before {
  width: 5px;
  height: 5px;
  background: #50d59c;
  border-radius: 50%;
  content: '';
}

.home-evidence-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-top: auto;
  padding-top: 13px;
  color: #728694;
  border-top: 1px solid #e4ebef;
  font-size: 8px;
}

.home-evidence-bar span {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.home-evidence-bar i {
  width: 6px;
  height: 6px;
  background: var(--home-cyan-deep);
  border-radius: 50%;
}

.home-evidence-bar b {
  color: #17799d;
}

.home-product-note {
  position: absolute;
  z-index: 3;
  display: inline-flex;
  align-items: center;
  min-height: 37px;
  gap: 7px;
  padding: 0 11px;
  color: #d9edf4;
  background: rgba(11, 31, 46, 0.9);
  border: 1px solid rgba(155, 218, 237, 0.2);
  border-radius: 9px;
  box-shadow: 0 14px 30px rgba(0, 8, 16, 0.3);
  backdrop-filter: blur(14px);
  font-size: 9px;
  font-weight: 600;
}

.home-product-note-top {
  top: 4px;
  right: 4px;
}

.home-product-note-top span {
  width: 7px;
  height: 7px;
  background: var(--home-cyan);
  border-radius: 50%;
  box-shadow: 0 0 0 4px rgba(25, 195, 241, 0.12);
  animation: home-pulse 1.8s ease-in-out infinite;
}

.home-product-note-bottom {
  right: -8px;
  bottom: 5px;
}

.home-product-note-bottom > span {
  color: #79d9ef;
  font-size: 8px;
}

.home-product-note-bottom svg {
  width: 14px;
  height: 14px;
}

.home-proof-strip {
  position: relative;
  z-index: 3;
  display: grid;
  width: min(1260px, calc(100% - 80px));
  grid-template-columns: repeat(4, minmax(0, 1fr));
  margin: -22px auto 0;
  padding: 0;
  overflow: hidden;
  background: rgba(255, 255, 255, 0.95);
  border: 1px solid rgba(16, 48, 66, 0.1);
  border-radius: 8px;
  box-shadow: 0 18px 45px rgba(22, 55, 72, 0.1);
  backdrop-filter: blur(16px);
}

.home-proof-strip > div {
  display: grid;
  gap: 5px;
  padding: 20px 24px;
}

.home-proof-strip > div + div {
  border-left: 1px solid #e8eef2;
}

.home-proof-strip dt {
  color: #17374c;
  font-size: 13px;
  font-weight: 700;
}

.home-proof-strip dd {
  margin: 0;
  color: #718392;
  font-size: 11px;
  line-height: 1.5;
}

.home-section {
  width: min(1260px, calc(100% - 48px));
  margin: 0 auto;
}

.home-workflow {
  padding: 120px 0 132px;
}

.home-section-heading {
  display: grid;
  grid-template-columns: minmax(0, 0.9fr) minmax(320px, 0.58fr);
  align-items: end;
  gap: clamp(40px, 8vw, 120px);
}

.home-section-kicker {
  display: flex;
  align-items: center;
  gap: 10px;
  margin: 0 0 18px;
  color: #598094;
  font-family: var(--font-code);
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0;
}

.home-section-kicker span {
  color: var(--home-cyan-deep);
}

.home-section-heading h2,
.home-ownership-copy h2 {
  margin: 0;
  color: #0b2335;
  font-family: 'Segoe UI Variable Display', 'Microsoft YaHei UI', sans-serif;
  font-size: 48px;
  font-weight: 720;
  letter-spacing: 0;
  line-height: 1.16;
}

.home-section-heading > p {
  max-width: 490px;
  margin: 0 0 3px;
  color: #647989;
  font-size: 14px;
  line-height: 1.85;
}

.home-workflow-list {
  display: grid;
  grid-template-columns: repeat(6, minmax(0, 1fr));
  gap: 0;
  margin: 68px 0 0;
  padding: 0;
  list-style: none;
}

.home-workflow-list li {
  position: relative;
  min-width: 0;
  padding: 0 20px 0 0;
}

.home-workflow-list li:not(:last-child)::after {
  position: absolute;
  top: 17px;
  right: 0;
  left: 48px;
  height: 1px;
  background: #cadae2;
  content: '';
}

.home-workflow-index {
  position: relative;
  z-index: 1;
  display: flex;
  align-items: center;
  gap: 8px;
}

.home-workflow-index span {
  display: grid;
  width: 35px;
  height: 35px;
  place-items: center;
  color: #087ca6;
  background: #e5f8fc;
  border: 1px solid #b9e9f3;
  border-radius: 9px;
  font-family: var(--font-code);
  font-size: 10px;
  font-weight: 700;
}

.home-workflow-index i {
  width: 5px;
  height: 5px;
  background: #91c8d7;
  border-radius: 50%;
}

.home-workflow-list small {
  display: block;
  margin-top: 24px;
  color: #87a0ae;
  font-family: var(--font-code);
  font-size: 9px;
  font-weight: 700;
  letter-spacing: 0;
}

.home-workflow-list h3 {
  margin: 8px 0 0;
  color: #16374d;
  font-size: 16px;
  font-weight: 700;
}

.home-workflow-list p {
  margin: 11px 0 0;
  color: #728594;
  font-size: 12px;
  line-height: 1.75;
}

.home-capability-stage {
  position: relative;
  padding: 116px 0 124px;
  overflow: hidden;
  background: #071522;
}

.home-capability-stage::before {
  position: absolute;
  inset: 0;
  opacity: 0.2;
  background-image:
    linear-gradient(rgba(129, 199, 221, 0.08) 1px, transparent 1px),
    linear-gradient(90deg, rgba(129, 199, 221, 0.08) 1px, transparent 1px);
  background-size: 54px 54px;
  content: '';
  mask-image: linear-gradient(180deg, #000, transparent 72%);
}

.home-capabilities {
  position: relative;
  z-index: 1;
}

.home-section-heading-light h2 {
  color: #f2f8fb;
}

.home-section-heading-light > p {
  color: #91a9b6;
}

.home-section-heading-light .home-section-kicker {
  color: #7ba5b6;
}

.home-section-heading-light .home-section-kicker span {
  color: var(--home-cyan);
}

.home-capability-grid {
  display: grid;
  grid-template-columns: repeat(12, minmax(0, 1fr));
  gap: 16px;
  margin-top: 64px;
}

.home-capability-card {
  position: relative;
  display: flex;
  min-width: 0;
  min-height: 420px;
  flex-direction: column;
  padding: 30px;
  overflow: hidden;
  background: #102536;
  border: 1px solid rgba(159, 211, 228, 0.12);
  border-radius: 8px;
  box-shadow: 0 24px 50px rgba(0, 6, 12, 0.16);
  transition:
    border-color 220ms ease,
    box-shadow 220ms ease,
    transform 220ms ease;
}

.home-capability-card:hover {
  border-color: rgba(93, 210, 240, 0.3);
  box-shadow: 0 30px 60px rgba(0, 5, 12, 0.25);
  transform: translateY(-4px);
}

.home-capability-card.is-strategy,
.home-capability-card.is-feedback {
  grid-column: span 7;
}

.home-capability-card.is-knowledge,
.home-capability-card.is-media {
  grid-column: span 5;
}

.home-capability-card.is-strategy {
  background: #102536;
}

.home-capability-card.is-knowledge {
  background: #eaf7fa;
  border-color: #cde7ed;
}

.home-capability-card.is-media {
  background: #f5f8fa;
  border-color: #dce7ec;
}

.home-capability-card.is-feedback {
  background: #0d2233;
}

.home-capability-copy {
  position: relative;
  z-index: 1;
  max-width: 560px;
}

.home-capability-icon {
  display: grid;
  width: 40px;
  height: 40px;
  place-items: center;
  color: var(--home-cyan);
  background: rgba(25, 195, 241, 0.09);
  border: 1px solid rgba(25, 195, 241, 0.17);
  border-radius: 10px;
}

.home-capability-icon svg {
  width: 22px;
  height: 22px;
  fill: none;
  stroke: currentColor;
  stroke-linecap: round;
  stroke-linejoin: round;
  stroke-width: 1.55;
}

.home-capability-copy > p {
  margin: 21px 0 0;
  color: #5bd0ee;
  font-family: var(--font-code);
  font-size: 9px;
  font-weight: 700;
  letter-spacing: 0;
}

.home-capability-copy h3 {
  max-width: 520px;
  margin: 11px 0 0;
  color: #f2f8fb;
  font-size: 28px;
  font-weight: 700;
  letter-spacing: 0;
  line-height: 1.3;
}

.home-capability-copy > span {
  display: block;
  max-width: 560px;
  margin-top: 12px;
  color: #9db2bd;
  font-size: 13px;
  line-height: 1.75;
}

.is-knowledge .home-capability-icon,
.is-media .home-capability-icon {
  color: #0781aa;
  background: #fff;
  border-color: #d3e8ee;
}

.is-knowledge .home-capability-copy > p,
.is-media .home-capability-copy > p {
  color: #0781aa;
}

.is-knowledge .home-capability-copy h3,
.is-media .home-capability-copy h3 {
  color: #123247;
}

.is-knowledge .home-capability-copy > span,
.is-media .home-capability-copy > span {
  color: #647c8c;
}

.home-capability-visual {
  position: relative;
  z-index: 1;
  margin-top: 24px;
}

.home-strategy-visual {
  display: grid;
  gap: 7px;
  padding: 14px;
  background: rgba(255, 255, 255, 0.04);
  border: 1px solid rgba(167, 216, 231, 0.12);
  border-radius: 8px;
}

.home-strategy-head {
  padding-bottom: 9px;
  color: #d9eaf0;
  border-bottom: 1px solid rgba(163, 211, 226, 0.1);
  font-size: 10px;
  font-weight: 700;
}

.home-strategy-head b {
  color: #58d2ee;
  font-family: var(--font-code);
  font-size: 8px;
}

.home-strategy-row {
  padding: 9px 10px;
  color: #b7cad3;
  background: rgba(255, 255, 255, 0.035);
  border: 1px solid transparent;
  border-radius: 8px;
  font-size: 10px;
}

.home-strategy-row.active {
  background: rgba(25, 195, 241, 0.08);
  border-color: rgba(25, 195, 241, 0.15);
}

.home-strategy-row i {
  flex: 0 0 auto;
  width: 6px;
  height: 6px;
  background: #4ed29a;
  border-radius: 50%;
}

.home-strategy-row span {
  flex: 1;
}

.home-strategy-row b {
  color: #75d8ee;
  font-size: 9px;
}

.home-strategy-trace {
  display: flex;
  align-items: center;
  gap: 7px;
  margin-top: 3px;
  color: #7695a5;
  font-size: 8px;
}

.home-strategy-trace span {
  width: 18px;
  height: 1px;
  background: var(--home-cyan);
}

.home-knowledge-visual {
  height: 136px;
}

.home-search-pill {
  display: flex;
  height: 34px;
  align-items: center;
  gap: 8px;
  padding: 0 11px;
  color: #718693;
  background: #fff;
  border: 1px solid #d8e7ec;
  border-radius: 9px;
  box-shadow: 0 8px 18px rgba(35, 76, 93, 0.06);
  font-size: 9px;
}

.home-search-pill svg {
  width: 15px;
  height: 15px;
  fill: none;
  stroke: #189bc2;
  stroke-linecap: round;
  stroke-width: 1.5;
}

.home-case-card {
  position: absolute;
  top: 48px;
  right: 0;
  left: 0;
  display: grid;
  gap: 4px;
  padding: 11px 13px;
  background: #fff;
  border: 1px solid #dce9ed;
  border-radius: 9px;
  box-shadow: 0 10px 20px rgba(37, 76, 91, 0.07);
}

.home-case-card.offset {
  top: 82px;
  right: 10px;
  left: 18px;
  opacity: 0.7;
}

.home-case-card b {
  color: #1387ac;
  font-family: var(--font-code);
  font-size: 7px;
}

.home-case-card span {
  color: #365568;
  font-size: 9px;
  font-weight: 600;
}

.home-case-card i {
  position: absolute;
  top: 11px;
  right: 12px;
  width: 6px;
  height: 6px;
  background: #4acb91;
  border-radius: 50%;
}

.home-media-visual {
  padding: 15px;
  background: #fff;
  border: 1px solid #dee9ed;
  border-radius: 8px;
  box-shadow: 0 12px 28px rgba(34, 70, 84, 0.07);
}

.home-media-file > div:nth-child(2) {
  display: grid;
  flex: 1;
  gap: 3px;
}

.home-media-play {
  display: grid;
  flex: 0 0 auto;
  width: 34px;
  height: 34px;
  place-items: center;
  background: #e9f8fb;
  border-radius: 8px;
}

.home-media-play span {
  width: 0;
  height: 0;
  margin-left: 2px;
  border-top: 5px solid transparent;
  border-bottom: 5px solid transparent;
  border-left: 7px solid #0b9fc8;
}

.home-media-file strong {
  color: #24475b;
  font-size: 10px;
}

.home-media-file small {
  color: #8b9ba5;
  font-size: 8px;
}

.home-media-file > b {
  color: #0a88b0;
  font-family: var(--font-code);
  font-size: 9px;
}

.home-media-progress {
  height: 5px;
  margin-top: 13px;
  overflow: hidden;
  background: #e6eef2;
  border-radius: 999px;
}

.home-media-progress span {
  display: block;
  width: 86%;
  height: 100%;
  background: var(--home-cyan-deep);
  border-radius: inherit;
}

.home-media-meta {
  margin-top: 11px;
}

.home-media-meta span {
  padding: 4px 7px;
  color: #68808e;
  background: #f3f7f9;
  border-radius: 999px;
  font-size: 8px;
}

.home-feedback-visual {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 120px;
  align-items: end;
  gap: 12px 18px;
  padding: 15px;
  background: rgba(255, 255, 255, 0.045);
  border: 1px solid rgba(157, 211, 228, 0.13);
  border-radius: 8px;
}

.home-feedback-summary {
  grid-column: 1 / -1;
}

.home-feedback-summary span {
  color: #7f9ead;
  font-size: 8px;
}

.home-feedback-summary strong {
  color: #e7f3f7;
  font-size: 10px;
}

.home-feedback-tags span {
  padding: 5px 8px;
  color: #8fdced;
  background: rgba(25, 195, 241, 0.08);
  border: 1px solid rgba(25, 195, 241, 0.12);
  border-radius: 999px;
  font-size: 8px;
}

.home-feedback-bars {
  display: grid;
  gap: 6px;
}

.home-feedback-bars i {
  display: block;
  width: var(--bar-width);
  height: 5px;
  margin-left: auto;
  background: var(--home-cyan-deep);
  border-radius: 999px;
}

.home-capability-link {
  display: inline-flex;
  width: fit-content;
  align-items: center;
  gap: 8px;
  margin-top: auto;
  padding-top: 22px;
  color: #60d4ef;
  font-size: 12px;
  font-weight: 700;
  text-decoration: none;
}

.home-capability-link:hover {
  color: #fff;
}

.is-knowledge .home-capability-link,
.is-media .home-capability-link {
  color: #087fa6;
}

.is-knowledge .home-capability-link:hover,
.is-media .home-capability-link:hover {
  color: #064f6b;
}

.home-ownership {
  display: grid;
  grid-template-columns: minmax(0, 0.82fr) minmax(500px, 1.18fr);
  align-items: center;
  gap: clamp(56px, 8vw, 112px);
  padding: 126px 0;
}

.home-ownership-copy > p:not(.home-section-kicker) {
  max-width: 550px;
  margin: 24px 0 0;
  color: #647a89;
  font-size: 14px;
  line-height: 1.85;
}

.home-ownership-copy ul {
  display: grid;
  gap: 12px;
  margin: 26px 0 0;
  padding: 0;
  color: #496273;
  font-size: 13px;
  list-style: none;
}

.home-ownership-copy li {
  position: relative;
  padding-left: 22px;
  line-height: 1.6;
}

.home-ownership-copy li::before {
  position: absolute;
  top: 5px;
  left: 0;
  width: 12px;
  height: 12px;
  background: #e3f7fb;
  border: 1px solid #a8e4f0;
  border-radius: 4px;
  content: '';
}

.home-ownership-copy li::after {
  position: absolute;
  top: 8px;
  left: 3px;
  width: 6px;
  height: 3px;
  border-bottom: 1.5px solid #087fa6;
  border-left: 1.5px solid #087fa6;
  content: '';
  transform: rotate(-45deg);
}

.home-inline-action {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  margin-top: 31px;
  color: #087da4;
  font-size: 13px;
  font-weight: 700;
  text-decoration: none;
}

.home-inline-action:hover {
  color: #055875;
}

.home-architecture-card {
  overflow: hidden;
  background: #fff;
  border: 1px solid #dbe7ec;
  border-radius: 8px;
  box-shadow: 0 26px 70px rgba(25, 58, 75, 0.12);
}

.home-architecture-card > header,
.home-architecture-card > footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  padding: 15px 18px;
  background: #f8fafb;
}

.home-architecture-card > header {
  border-bottom: 1px solid #e5ecef;
}

.home-architecture-card > header div,
.home-architecture-card > footer span {
  display: inline-flex;
  align-items: center;
  gap: 7px;
}

.home-architecture-card > header i,
.home-architecture-card > footer i {
  width: 7px;
  height: 7px;
  background: #45c78a;
  border-radius: 50%;
  box-shadow: 0 0 0 4px rgba(69, 199, 138, 0.11);
}

.home-architecture-card > header span {
  color: #576e7d;
  font-family: var(--font-code);
  font-size: 10px;
}

.home-architecture-card > header b {
  padding: 5px 8px;
  color: #08799f;
  background: #e9f8fb;
  border-radius: 999px;
  font-size: 9px;
}

.home-architecture-map {
  display: grid;
  grid-template-columns:
    minmax(94px, 1fr) 28px minmax(94px, 1fr) 28px minmax(94px, 1fr)
    28px minmax(94px, 1fr);
  align-items: center;
  gap: 7px;
  padding: 32px 20px 24px;
}

.home-architecture-node {
  display: grid;
  min-height: 92px;
  align-content: center;
  gap: 8px;
  padding: 12px;
  text-align: center;
  background: #f4f8fa;
  border: 1px solid #dce7ec;
  border-radius: 8px;
}

.home-architecture-node span {
  color: #708493;
  font-size: 9px;
}

.home-architecture-node strong {
  color: #27495d;
  font-size: 10px;
  line-height: 1.45;
}

.home-architecture-node.is-agent {
  background: #e9f9fc;
  border-color: #aee7f2;
  box-shadow: 0 9px 20px rgba(19, 157, 195, 0.08);
}

.home-architecture-node.is-agent strong {
  color: #087ca4;
}

.home-architecture-map > svg {
  width: 24px;
  fill: none;
  stroke: #9fb1bb;
  stroke-linecap: round;
  stroke-linejoin: round;
  stroke-width: 1.4;
}

.home-stack-list {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 7px;
  padding: 0 20px 30px;
}

.home-stack-list span {
  padding: 6px 9px;
  color: #667c8b;
  background: #f3f7f9;
  border: 1px solid #e0e8ec;
  border-radius: 999px;
  font-family: var(--font-code);
  font-size: 8px;
}

.home-architecture-card > footer {
  color: #82929c;
  border-top: 1px solid #e5ecef;
  font-size: 9px;
}

.home-final-cta {
  position: relative;
  display: flex;
  width: 100%;
  min-height: 380px;
  align-items: center;
  flex-direction: column;
  justify-content: center;
  margin: 0;
  padding: 70px 24px;
  overflow: hidden;
  color: #fff;
  text-align: center;
  background: #071522;
  border-block: 1px solid rgba(139, 208, 229, 0.13);
  box-shadow: 0 26px 70px rgba(12, 38, 54, 0.18);
}

.home-final-cta > p,
.home-final-cta > h2,
.home-final-cta > span,
.home-final-cta > div:last-child {
  position: relative;
  z-index: 2;
}

.home-final-cta > p {
  margin: 0;
  color: #5dd2ee;
  font-family: var(--font-code);
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0;
}

.home-final-cta h2 {
  max-width: 780px;
  margin: 15px 0 0;
  color: #f5fafc;
  font-family: 'Segoe UI Variable Display', 'Microsoft YaHei UI', sans-serif;
  font-size: 52px;
  font-weight: 720;
  letter-spacing: 0;
  line-height: 1.25;
}

.home-final-cta > span {
  margin-top: 14px;
  color: #9fb4bf;
  font-size: 14px;
}

.home-final-cta > div:last-child {
  margin-top: 28px;
}

.home-secondary-action-dark {
  color: #d9e8ee;
}

.home-footer {
  display: grid;
  width: min(1260px, calc(100% - 48px));
  min-height: 118px;
  grid-template-columns: auto minmax(0, 1fr) auto;
  align-items: center;
  gap: 24px;
  margin: 0 auto;
  color: #718492;
}

.home-footer-brand {
  display: inline-flex;
  align-items: center;
  gap: 9px;
  color: #17364a;
  text-decoration: none;
}

.home-footer-brand > span {
  position: relative;
  width: 28px;
  height: 28px;
  background: var(--home-cyan-deep);
  border-radius: 8px;
  box-shadow: 0 8px 18px rgba(0, 159, 213, 0.16);
}

.home-footer-brand > span::after {
  position: absolute;
  top: 8px;
  left: 10px;
  width: 0;
  height: 0;
  border-top: 6px solid transparent;
  border-bottom: 6px solid transparent;
  border-left: 8px solid #fff;
  content: '';
}

.home-footer-brand strong {
  font-size: 15px;
}

.home-footer p {
  margin: 0;
  font-size: 11px;
}

.home-footer nav {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 18px;
}

.home-footer nav a {
  color: #617785;
  font-size: 11px;
  text-decoration: none;
}

.home-footer nav a:hover {
  color: #087da4;
}

.home-primary-action:focus-visible,
.home-secondary-action:focus-visible,
.home-capability-link:focus-visible,
.home-inline-action:focus-visible,
.home-footer a:focus-visible {
  outline: 3px solid rgba(25, 195, 241, 0.32);
  outline-offset: 3px;
}

.home-hero-copy > * {
  animation: home-rise 620ms cubic-bezier(0.22, 1, 0.36, 1) both;
}

.home-hero-copy > :nth-child(2) {
  animation-delay: 70ms;
}

.home-hero-copy > :nth-child(3) {
  animation-delay: 130ms;
}

.home-hero-copy > :nth-child(4) {
  animation-delay: 190ms;
}

.home-hero-copy > :nth-child(5) {
  animation-delay: 240ms;
}

.home-product-stage {
  animation: home-stage-in 760ms 150ms cubic-bezier(0.22, 1, 0.36, 1) both;
}

@keyframes home-rise {
  from {
    transform: translateY(18px);
  }
  to {
    transform: translateY(0);
  }
}

@keyframes home-stage-in {
  from {
    transform: translateX(28px) scale(0.98);
  }
  to {
    transform: translateX(0) scale(1);
  }
}

@keyframes home-pulse {
  0%,
  100% {
    opacity: 0.7;
    transform: scale(0.88);
  }
  50% {
    opacity: 1;
    transform: scale(1.1);
  }
}

@media (max-width: 1180px) {
  .home-hero {
    min-height: auto;
    grid-template-columns: minmax(0, 1fr);
    gap: 54px;
  }

  .home-hero h1 {
    font-size: 54px;
  }

  .home-hero-copy {
    max-width: 760px;
  }

  .home-hero h1 {
    max-width: 760px;
  }

  .home-product-stage {
    width: min(760px, 100%);
    justify-self: center;
  }

  .home-proof-strip {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .home-proof-strip > div:nth-child(3) {
    border-top: 1px solid #e8eef2;
    border-left: 0;
  }

  .home-proof-strip > div:nth-child(4) {
    border-top: 1px solid #e8eef2;
  }

  .home-workflow-list {
    grid-template-columns: repeat(3, minmax(0, 1fr));
    gap: 42px 0;
  }

  .home-workflow-list li:nth-child(3)::after,
  .home-workflow-list li:last-child::after {
    display: none;
  }

  .home-ownership {
    grid-template-columns: 1fr;
  }

  .home-ownership-copy {
    max-width: 720px;
  }

  .home-architecture-card {
    width: min(760px, 100%);
  }
}

@media (max-width: 900px) {
  .home-hero {
    gap: 34px;
    padding-block: 48px;
  }

  .home-hero h1 {
    font-size: 48px;
  }

  .home-product-stage {
    height: 240px;
    padding: 0;
    overflow: hidden;
  }

  .home-section-heading {
    grid-template-columns: 1fr;
    gap: 24px;
  }

  .home-section-heading > p {
    max-width: 680px;
  }

  .home-section-heading h2,
  .home-ownership-copy h2 {
    font-size: 44px;
  }

  .home-capability-card.is-strategy,
  .home-capability-card.is-feedback,
  .home-capability-card.is-knowledge,
  .home-capability-card.is-media {
    grid-column: span 6;
  }

  .home-architecture-map {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .home-architecture-map > svg {
    display: none;
  }
}

@media (max-width: 680px) {
  .home-section,
  .home-footer {
    width: min(100% - 24px, 1260px);
  }

  .home-hero {
    gap: 28px;
    margin: 0;
    padding: 32px 24px 24px;
  }

  .home-hero h1 {
    overflow-wrap: anywhere;
    font-size: 38px;
    letter-spacing: 0;
  }

  .home-hero-description {
    font-size: 14px;
  }

  .home-hero-actions {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    align-items: stretch;
  }

  .home-primary-action,
  .home-secondary-action {
    width: 100%;
  }

  .home-product-stage {
    height: 190px;
    padding: 0;
    overflow: hidden;
  }

  .home-dashboard {
    width: 100%;
    max-width: 100%;
    transform: none;
  }

  .home-dashboard-bar {
    grid-template-columns: 1fr auto;
  }

  .home-dashboard-bar > span {
    display: none;
  }

  .home-dashboard-body {
    min-height: 430px;
    grid-template-columns: 1fr;
  }

  .home-dashboard-sidebar {
    display: none;
  }

  .home-dashboard-content {
    padding: 18px 13px 14px;
  }

  .home-preview-grid {
    grid-template-columns: 1fr;
  }

  .home-review-panel {
    display: grid;
    grid-template-columns: 30px minmax(0, 1fr);
    gap: 3px 10px;
  }

  .home-review-panel > span,
  .home-review-panel > strong,
  .home-review-panel ul {
    margin-top: 0;
  }

  .home-review-panel > span,
  .home-review-panel > strong {
    grid-column: 2;
  }

  .home-review-panel ul {
    grid-column: 1 / -1;
    grid-template-columns: repeat(3, minmax(0, 1fr));
    margin-top: 8px;
  }

  .home-product-note-top {
    right: -4px;
  }

  .home-product-note-bottom {
    right: -4px;
  }

  .home-proof-strip {
    width: min(100% - 36px, 1260px);
    grid-template-columns: repeat(2, minmax(0, 1fr));
    margin-top: -17px;
  }

  .home-proof-strip > div:nth-child(3),
  .home-proof-strip > div:nth-child(4) {
    border-top: 1px solid #e8eef2;
  }

  .home-proof-strip > div:nth-child(3) {
    border-left: 0;
  }

  .home-proof-strip > div {
    padding: 16px;
  }

  .home-workflow,
  .home-ownership {
    padding: 88px 0;
  }

  .home-workflow-list {
    grid-template-columns: 1fr;
    gap: 0;
    margin-top: 48px;
  }

  .home-workflow-list li {
    padding: 0 0 34px 54px;
  }

  .home-workflow-list li:not(:last-child)::after {
    top: 36px;
    bottom: 0;
    left: 17px;
    width: 1px;
    height: auto;
  }

  .home-workflow-index {
    position: absolute;
    top: 0;
    left: 0;
  }

  .home-workflow-index i {
    display: none;
  }

  .home-workflow-list small {
    margin-top: 0;
  }

  .home-workflow-list h3 {
    margin-top: 5px;
  }

  .home-workflow-list p {
    margin-top: 7px;
  }

  .home-capability-stage {
    padding: 86px 0;
  }

  .home-capability-grid {
    margin-top: 44px;
  }

  .home-capability-card.is-strategy,
  .home-capability-card.is-feedback,
  .home-capability-card.is-knowledge,
  .home-capability-card.is-media {
    grid-column: 1 / -1;
  }

  .home-capability-card {
    min-height: 390px;
    padding: 23px;
  }

  .home-capability-copy h3 {
    font-size: 24px;
  }

  .home-feedback-visual {
    grid-template-columns: 1fr;
  }

  .home-feedback-summary {
    align-items: flex-start;
    flex-direction: column;
  }

  .home-feedback-bars {
    display: none;
  }

  .home-architecture-map {
    grid-template-columns: 1fr;
    padding-inline: 16px;
  }

  .home-architecture-node {
    min-height: 74px;
  }

  .home-architecture-card > footer {
    align-items: flex-start;
    flex-direction: column;
  }

  .home-final-cta {
    min-height: 420px;
    padding: 60px 20px;
  }

  .home-section-heading h2,
  .home-ownership-copy h2,
  .home-final-cta h2 {
    font-size: 36px;
  }

  .home-final-cta > div:last-child {
    width: 100%;
    align-items: stretch;
    flex-direction: column;
  }

  .home-footer {
    grid-template-columns: 1fr;
    justify-items: center;
    gap: 14px;
    padding: 34px 0 52px;
    text-align: center;
  }

  .home-footer nav {
    justify-content: center;
  }
}

@media (prefers-reduced-motion: reduce) {
  .home-hero-copy > *,
  .home-product-stage,
  .home-product-note-top span {
    animation: none;
  }

  .home-primary-action,
  .home-secondary-action,
  .home-capability-card {
    transition: none;
  }
}
</style>
