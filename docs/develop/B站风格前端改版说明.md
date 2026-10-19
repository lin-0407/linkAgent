# B 站风格前端改版

## 需求与范围

移除独立展示首页，让个人创作者直接进入创作台。按照 B 站创作中心取向统一全部一级页面的导航、配色、卡片、表单、间距与手机排版。

本轮只修改前端呈现和入口导航。发布方案、制作蓝图、成片试映、实际发布、BV 绑定、反馈和复盘仍使用原有状态与门禁，没有新增接口、数据库字段或依赖。

## 先出图，再落实到组件

- 使用内置 imagegen 生成设计图，未指定或确认底层具体模型版本。
- 完整提示词：[设计提示词](../design/bilibili-redesign-prompt.md)。
- 设计参考：[创作台与参考案例设计图](../design/bilibili-workbench.png)。
- 安装并读取 Anthropic 的 frontend-design Skill，按用户确定的 B 站风格实施。来源为 anthropics/claude-code 的 plugins/frontend-design/skills/frontend-design。
- 图中的示例封面、项目和数字仅表达布局；产品继续使用接口真实返回的数据。

## 视觉方案

| 用途 | 实现 |
|---|---|
| 画布与内容 | 浅灰 #f6f7f8、白色 #ffffff |
| 品牌识别 | B 站粉 #fb7299、辅助青蓝 #00aeec |
| 主操作 | 深粉 #d13f6c，白字对比度约 4.53:1 |
| 文字与边界 | 标题 #18191c、正文 #4e535b、边框 #e9eaec |
| 字体 | 沿用中文无衬线字体，不添加远程字体依赖 |
| 圆角 | 控件 8px、内容卡片 12px、弹窗 16px |
| 桌面导航 | 64px 顶栏、192px 侧栏；中等屏幕侧栏 172px |
| 手机导航 | 820px 以下收为页面菜单，全部六个入口和指南仍可访问 |

布局以可用内容区宽度计算，不再用包含侧栏的 100vw 计算业务容器。已有任务的步骤改为横向，避免全局导航与原流程侧栏重复占用工作区。

## 改动位置

- App.vue：共享桌面侧栏和手机菜单，保留原有弹窗焦点管理。
- router/index.ts、NotFoundPage.vue：根地址转到 /creator，错误页面返回创作台。
- 删除 HomePage.vue。
- theme.css：统一颜色、圆角和历史变量。
- workbench.css、main.ts：新版布局及全部一级页面的样式。
- CreatorWorkspace.vue、AiCreationConsole.vue：输入区排版、真实最近任务、流程栏和完整六步的开发环境布局预览。
- KnowledgeWorkspace.vue、ProjectListWorkspace.vue、UsageLogPage.vue：统一标题和内容表达。
- 四个设置分区组件：将折叠标题按钮与刷新按钮分开，去掉嵌套 button。
- ProductionPositioningForm.vue：移除对用户没有帮助的阶段编号；制作蓝图和成片试映同样采用新版内容卡片。

## 验证边界

前端预览通过独立的 127.0.0.1:15173 端口运行。浏览器中的 API 由隔离的样例响应替代，未启动后端、未调用业务模型、未写数据库或 Redis。

页面样例截图只能证明排版与页面交互；不能作为真实模型生成、视频上传、B 站采集或完整业务链路成功的证据。

## 本轮验证记录

| 实际执行 | 观察到的结果 |
|---|---|
| 在 frontend 下执行 npm run build-only | Vite 生产构建通过，2147 个模块转换完成；独立展示首页不再出现在路由中 |
| node .tmp/frontend-redesign/preview.cjs | Edge 中检查 8 个页面或状态 × 5 档宽度，共 40 组；无文档横向溢出、无 JavaScript 页面错误 |
| node .tmp/frontend-redesign/workflow-states.cjs | 制作蓝图和已有成片任务的试映页 × 5 档宽度，共 10 组；无文档横向溢出、无 JavaScript 页面错误 |
| node .tmp/frontend-redesign/interactions.cjs | 13 组页面交互检查通过：路由、草稿、指南、项目筛选、案例详情与导入、记忆取消删除、日志筛选、设置与焦点、步骤禁用、手机菜单、空数据、错误提示及绑定视频卡片 |
| git diff --check | 无补丁空白错误 |

宽度为 1440、1280、768、390、320px。主页面截图分别使用 900、720、1024、844、740px 高度，制作与试映状态另用桌面 900px、窄屏 844px 高度。

临时预览的实际启动方式为在 frontend 目录使用 Node 运行：

```powershell
node node_modules/vite/bin/vite.js --host 127.0.0.1 --port 15173 --strictPort
```

上述浏览器脚本仅用于本轮检查，已和日志、临时截图一起删除。验证结果保存在 [validation.json](../design/validation.json)，选定截图保存在 [桌面创作台](../design/creator-desktop.png)、[手机创作台](../design/creator-mobile.png) 和 [参考案例](../design/knowledge-desktop.png)。

本轮启动的 Vite 进程 PID 22108 已关闭，15173 端口已释放；所有浏览器隔离上下文均已关闭。临时目录通过明确文件清单逐项清理，再删除空目录。未启动后端、未写入数据库或 Redis，未改动作者已有服务。未运行 npm run type-check，也没有修改 package.json 的 Node.js 版本。

## 作者查看方式

本机 Docker 使用 `http://localhost:8088`。源码修改不会自动同步到已有前端镜像；在仓库根目录执行以下命令更新页面：

```powershell
docker compose -f docker-compose.yml -f docker-compose.local.yml up -d --no-deps --build frontend
```

该命令只更新前端服务，沿用当前后端和中间件。前端 Dockerfile 使用 `npm run build-only`，避免间接执行项目规则禁止的类型检查。更新后刷新浏览器，预期根地址进入 `/creator`，桌面显示左侧工作台导航；若旧标签页仍显示已加载的旧页面，可按 `Ctrl+F5` 重新加载。

本地开发可以在 frontend 目录运行 npm run dev，打开命令输出的地址。预期直接进入创作台；桌面左侧有六个业务入口，手机可通过顶栏菜单进入全部页面。可再访问 /creator?layoutPreview=prepublish 检查内置的发布方案布局样例，此入口只在开发环境生效。

实际数据与模型功能需要连接作者自己的后端后检查；本轮不把样例响应当作业务联调结果。验证结束后关闭自行启动的前端服务。

## 2026-10-18 Docker 页面更新核验

作者打开 `localhost:8088` 后仍看到旧首页。实际读取该地址，确认由 Nginx 提供 10 月 15 日的静态资源，入口为 `/js/index-CDltUBjV.js`。之前的改版只更新了源码和独立预览，没有更新运行中的前端容器。

本次执行上述 Compose 命令，只重建 `frontend`。镜像内实际执行 `npm run build-only`，2147 个模块转换完成。入口更新为 `/js/index-mt7tcf_7.js`，包含新版侧栏，且已不含 HomePage 路由引用。对比更新前后的容器 ID 和启动时间，后端、MySQL、Redis、Milvus、etcd 均保持原实例运行。

通过临时脚本 `node .tmp/frontend-deployment-check.cjs` 驱动 Edge，直接检查 `http://localhost:8088`，未使用模拟 API。1440px 和 390px 两档共 8 项检查通过：根路径跳转、新版六项导航、创作台与项目页无横向溢出、桌面或手机菜单跳转、项目页直接刷新。未出现 JavaScript 页面错误。

核验中默认 B 站账号接口返回 404，响应明确表示尚未绑定 UID，前端已有空状态处理。参考案例页初次检查出现部分外站封面 403；既有逻辑会隐藏失败图片并尝试刷新公开资料，本次未修改这段逻辑，也未把它记为封面全部加载成功。页面还自动发出既有的 `POST /api/settings/connectivity/check` 连通性探测；本次没有主动提交创作或执行模型生成、上传、采集等业务操作。

验证结果保存于 [部署核验记录](../design/deployment-validation.json)，真实运行截图见 [桌面创作台](../design/creator-live-desktop.png) 和 [手机创作台](../design/creator-live-mobile.png)。本次只证明新版页面已经部署且上述导航与布局可用，没有重跑完整业务链路。浏览器已关闭，临时检查脚本已删除；更新后的前端容器保留运行供作者使用。
