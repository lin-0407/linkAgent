# Learnings

Corrections, insights, and knowledge gaps captured during development.

**Categories**: correction | insight | knowledge_gap | best_practice

---

## [LRN-20260531-002] correction

**Logged**: 2026-05-31T00:00:00+08:00
**Priority**: high
**Status**: pending
**Area**: frontend

### Summary
首次使用的文件导入入口不能只放在编辑态，必须在创建态直接可见并可用。

### Details
用户指出当前导入设计不合理：第一次进入创作工作台时，最先需要的就是导入本地文稿或字幕，但入口被放在已有任务的编辑模式里，导致首轮用户无法直接使用。正确做法是把导入入口放在任务输入区前部，创建态本地回填，编辑态再走后端覆盖。

### Suggested Action
在所有类似的材料导入、样例导入、起始数据导入场景里，优先检查“新用户第一次进入时是否可直接使用”，不要把核心入口藏到编辑态或二级操作里。

### Metadata
- Source: user_feedback
- Related Files: link-agent-frontend/src/components/CreatorWorkspace.vue
- Tags: ux, onboarding, import, correction

---

## [LRN-20260531-001] correction

**Logged**: 2026-05-31T00:00:00+08:00
**Priority**: high
**Status**: pending
**Area**: infra

### Summary
后续不要默认宿主机有 Docker，也不要把项目开发误导到本地部署方向。

### Details
用户明确说明宿主机没有 Docker，MySQL 和 Redis 都在虚拟机里的 Docker 中运行。后续开发应围绕项目功能本身推进，避免再主动带入本地 Docker 演示、宿主机容器化或部署步骤。

### Suggested Action
在涉及运行环境、数据库或缓存时，先确认项目依赖实际部署位置，再决定是否需要本机环境或虚拟机环境配合。

### Metadata
- Source: user_feedback
- Related Files: AGENTS.md
- Tags: docker, environment, deployment, correction

---
