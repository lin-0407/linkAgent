# 项目文档索引

本文件统一记录阶段文档、功能说明和踩坑记录。`AGENTS.md` 只保留项目协作规则，避免随着阶段增加变得过长。


## 项目访问入口

| 类型 | 地址 |
|---|---|
| 项目域名 | <https://www.linkagent.cloud> |

## 阶段文档

| 文档 | 路径 |
|---|---|
| 阶段 5.4 - Agent 内核结构化输出升级 | `/docs/develop/阶段5.4-Agent内核结构化输出升级.md` |
| Agent 内核结构化输出升级说明 | `/docs/reference/Agent内核结构化输出升级说明.md` |
| 阶段 5.4 - Agent 内核结构化输出升级踩坑记录 | `/docs/error/阶段5.4-Agent内核结构化输出升级踩坑记录.md` |
| 阶段 5.7 - 创作者视频类型语境库 | `/docs/develop/阶段5.7-创作者视频类型语境库.md` |
| 创作者视频类型语境库说明 | `/docs/reference/创作者视频类型语境库说明.md` |
| 阶段 5.7 - 创作者视频类型语境库踩坑记录 | `/docs/error/阶段5.7-创作者视频类型语境库踩坑记录.md` |
| 阶段 5.9 - LLM API 开销统计与全链路追溯 | `/docs/develop/阶段5.9-LLM API开销统计与全链路追溯.md` |
| LLM API 开销统计与全链路追溯说明 | `/docs/reference/LLM API开销统计与全链路追溯说明.md` |
| 阶段 5.9 - LLM API 开销统计与全链路追溯踩坑记录 | `/docs/error/阶段5.9-LLM API开销统计与全链路追溯踩坑记录.md` |
| 阶段 5.10 - 发布前优化 Agent 化与步骤级开销追溯 | `/docs/develop/阶段5.10-发布前优化Agent化与步骤级开销追溯.md` |
| 发布前优化 Agent 化与步骤级开销追溯说明 | `/docs/reference/发布前优化Agent化与步骤级开销追溯说明.md` |
| 阶段 5.10 - 发布前优化 Agent 化与步骤级开销追溯踩坑记录 | `/docs/error/阶段5.10-发布前优化Agent化与步骤级开销追溯踩坑记录.md` |
| 阶段 6 - Agent PaE 与 Multi Agent 模式 | `/docs/develop/阶段6-Agent PAE与Multi Agent模式.md` |
| Agent PaE 与 Multi Agent 模式说明 | `/docs/reference/Agent PAE与Multi Agent模式说明.md` |
| 阶段 6 - Agent PaE 与 Multi Agent 模式踩坑记录 | `/docs/error/阶段6-Agent PAE与Multi Agent模式踩坑记录.md` |
| 阶段 6.1 - Multi Agent 并发与引用审查 | `/docs/develop/阶段6.1-Multi Agent并发与引用审查.md` |
| Multi Agent 并发与引用审查说明 | `/docs/reference/Multi Agent并发与引用审查说明.md` |
| 阶段 6.1 - Multi Agent 并发与引用审查踩坑记录 | `/docs/error/阶段6.1-Multi Agent并发与引用审查踩坑记录.md` |
| 阶段 6.2 - PaE 重规划 | `/docs/develop/阶段6.2-PaE重规划.md` |
| PaE 重规划说明 | `/docs/reference/PaE重规划说明.md` |
| 阶段 6.2 - PaE 重规划踩坑记录 | `/docs/error/阶段6.2-PaE重规划踩坑记录.md` |
| 阶段 6.3 - AI 交互式创作与视频复盘工作流重构 | `/docs/develop/阶段6.3-AI交互式创作与视频复盘工作流重构.md` |
| AI 交互式创意方案入口说明 | `/docs/reference/AI交互式创意方案入口说明.md` |
| 阶段 6.3 P0-1 - AI 创意方案入口踩坑记录 | `/docs/error/阶段6.3-P0-1-AI创意方案入口踩坑记录.md` |
| 发布前优化 AI 交互台说明 | `/docs/reference/发布前优化AI交互台说明.md` |
| 阶段 6.3 P0-2 - 发布前优化 AI 交互台踩坑记录 | `/docs/error/阶段6.3-P0-2-发布前优化AI交互台踩坑记录.md` |
| 阶段 6.3 P0-3 - BV 绑定与 UID 绑定（后端） | `/docs/reference/` — 参见阶段 6.3 总方案文档 §14-15 |
| 阶段 6.3 P0-3 - B站账号、BV绑定与视频分析页（前端） | `/docs/reference/` — 参见阶段 6.3 总方案文档 §16 |
| B站账号与视频绑定 API 接口 | `/backend/src/main/java/com/link/linkagent/creator/bilibili/`（Controller / Service / Mapper） |
| B站账号与视频绑定前端组件 | `/frontend/src/components/creator/BvBindingPanel.vue`、`BilibiliAccountPanel.vue`、`LinkedVideoCard.vue`、`LinkedVideoGrid.vue` |
| 视频分析页 | `/frontend/src/views/VideoAnalysisPage.vue` — 路由 `/video-analysis` |
| LinkAgent 前端全链路用户化重构方案 | `/docs/develop/项目前端业务流程重构.md` |
| 前端 P0 用户化重构落地记录 | `/docs/develop/前端P0用户化重构落地.md` |
| 前端 P0 用户化重构说明 | `/docs/reference/前端P0用户化重构说明.md` |
| 前端 P0 用户化重构阶段问题整理 | `/docs/error/前端P0用户化重构阶段问题整理.md` |
| P0 主链路验收清单 | `/docs/develop/P0主链路验收清单.md` |
| P0 重构前代码审计报告 | `/docs/develop/P0重构前代码审计报告.md` |
| 后端体验提升方案（记忆系统/SSE/容错/反馈） | `/docs/develop/linkAgent-backend-improvement-plan.md` |
| 前端体验提升方案（Tab工作台/时间轴/建议卡片/移动端） | `/docs/develop/linkAgent-frontend-improvement-plan.md` |
| 前端体验优化落地（阶段一：P0+P1 四件套） | `/docs/develop/前端体验优化落地-阶段一.md` |
| 前端体验优化落地（阶段二拆分方案） | `/docs/develop/前端体验优化落地-阶段二-拆分方案.md` |
| 前端体验优化落地（阶段二：CreatorWorkspace 模板拆分） | `/docs/develop/前端体验优化落地-阶段二.md` |
| 前端 P0-4 视觉和响应式收口 | `/docs/develop/前端P0-4视觉和响应式收口.md` |
| 前端 P0-4 视觉和响应式收口问题整理 | `/docs/error/前端P0-4视觉和响应式收口问题整理.md` |
| 前端 P0-4 视觉和响应式收口说明 | `/docs/reference/前端P0-4视觉和响应式收口说明.md` |
| 发布前优化金标准集 V1 设计 | `/docs/develop/发布前优化金标准集V1设计.md` |
| 发布前优化金标准集 V1 机器可读样例 | `/docs/develop/pre_publish_golden_v1_cases.jsonl` |
| 阶段 6.4 - 发布前优化证据化建议链路 | `/docs/develop/阶段6.4-发布前优化证据化建议链路.md` |
| 发布前优化证据化建议链路说明 | `/docs/reference/发布前优化证据化建议链路说明.md` |
| 阶段 6.4 - 发布前优化证据化建议链路踩坑记录 | `/docs/error/阶段6.4-发布前优化证据化建议链路踩坑记录.md` |
| 阶段 6.5 - 发布前优化建议审查器 | `/docs/develop/阶段6.5-发布前优化建议审查器.md` |
| 发布前优化建议审查器说明 | `/docs/reference/发布前优化建议审查器说明.md` |
| 阶段 6.5 - 发布前优化建议审查器踩坑记录 | `/docs/error/阶段6.5-发布前优化建议审查器踩坑记录.md` |
| 提示词模板资产治理与初始化乱码修复 | `/docs/develop/提示词模板资产治理与初始化乱码修复.md` |
| 提示词模板资产治理说明 | `/docs/reference/提示词模板资产治理说明.md` |
| 提示词模板初始化乱码修复记录 | `/docs/error/提示词模板初始化乱码修复记录.md` |
