# 固定工程后台执行 S3c 验证记录

日期：2026-10-20。仅使用本轮创建的隔离测试资源，不调用真实付费供应商或写入作者的媒体库。

## 验证对象与环境

首轮暂存树 `6e1ee328dd67d49a25828dd8b0ec29bf7f16e72a`：97 项定向验证通过，包含 77 项真实隔离 MySQL 测试及 20 项新增文件/执行器分支测试。84.167 秒中文视频实际由执行器完成渲染、八个私有对象往返、版本登记、登记恢复、原 FFprobe 和删除。

随后增加 Spring 定时调度及真实媒体取消测试，最终验证对象为暂存树 `18fcb1d68e6ae208752e35079d1fa5ffd6a9d876`。下述完整回归 322 项通过，0 失败、0 错误、0 跳过，耗时 2 分 56 秒；569 个主源码和 126 个测试源码编译通过。首轮 97 项不再额外加到最终统计。

测试镜像复用现有 S2 的 Python/FFmpeg/中文字体，以及本机已有 Java 21/Maven 镜像中的运行时和依赖缓存。显式只读挂载当前暂存快照的 renderer，避免复用镜像内旧渲染源码。测试容器限制 2 CPU、2GiB 和 256 个进程；MySQL 8.4 与 RustFS 均由 Testcontainers 新建。

实际启用：

```text
LINKAGENT_GENERATED_NATIVE=true
LINKAGENT_RENDERER_NATIVE_TESTS=true
LINKAGENT_RENDERER_MODULE_ROOT=/opt/renderer
LINKAGENT_RENDERER_SAMPLE_ROOT=/spoken
LINKAGENT_RENDER_TEST_SECRET=dummy-not-a-credential
TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal
TESTCONTAINERS_RYUK_DISABLED=true
```

虚拟的 secret 标识只验证子进程环境隔离，不是真实密钥。MySQL、RustFS 和测试视频与作者服务隔离。

## 最终验证命令

```text
mvn -B -Dstyle.color=never -Dtest=FixedProjectJobFilesTest,FixedProjectWorkerTest,GeneratedDraftContentHashTest,GeneratedDraftServiceTest,FixedProjectRenderServiceNativeTest,RenderArtifactVerifierTest,MediaVersionPersistenceIntegrationTest,MediaUploadServiceTest,CreatorMediaWorkflowGateServiceTest,ProductionPlanGateServiceTest,MediaDeletionServiceTest,CreatorBilibiliServiceTest,ProductionPlanPersistenceServiceTest,CreatorPublishedVideoContextServiceTest,CreatorFeedbackServiceTest,CreatorReportServiceTest,PhaseZeroTransactionBoundaryTest,PhaseZeroStaleResultTest,CreationWorkspaceControllerTest,CreationRuntimeControllerTest,MediaProcessingServiceTest,PreflightReviewServiceTest,AgentExecutorCacheReplayTest,AgentExecutorRetainedTokenBudgetTest,AgentExecutorToolHistoryTest,SummaryMemoryTest,SummaryMemoryGuardTest,SummaryReviewerTest,RedisShortTermMemoryStoreSerializationTest,StrictFunctionCallingClientTest,LLMServiceThinkingRequestTest test
```

## 证据范围

- 真实文件：冻结工程不跟随源 JSON 的后续改动；独立尝试、资产字节摘要、路径越界、保留目录和取消均有实际文件断言。本组协议资产不冒充成片。
- 真实 MySQL：工程附加的授权/幂等/领取边界、双执行器竞争、等待依赖取消、登记后接管和接管次数上限。
- 实际媒体与存储：中文工程实际编码，存入真实 RustFS，精确版本登记，移走本测试工作目录后恢复同一版，原 FFprobe 读取及八个对象删除；正式发布门禁仍阻断。
- 新增调度和取消测试：使用最小 Spring 调度上下文自动领取；取消实际运行中的 Python/FFmpeg，检查所观察媒体进程退出，不向存储写入或登记假成片。
- 失租、断库与线程关闭的分支使用协议替身；不把它们单独写成真实部署故障注入。

最终分组包括 78 项真实 MySQL 测试、13 项工程文件检查、7 项执行器分支、16 项原生渲染桥接及其余原有核心回归。NativeTest 和真实媒体开关均已开启，不能将未开启开关时的默认测试结果当作相同证据。

实际日志：

```text
S3C_NATIVE_SCHEDULED_CANCEL_OK stoppedMediaProcesses=2
S3C_NATIVE_WORKER_RESTART_PROBE_DELETE_OK durationMs=84167 files=8 generation=2
Tests run: 322, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

后台制作测试使用 5 秒租约、1 秒心跳，首次视频登记仍为第一执行代数，之后从登记事实恢复为第二代数；存储对象和视频版本均没有增加。确认记录与制作步骤为数据库夹具，不代表自动蓝图或真实确认页面已完成。

## 自检与边界

自检修正：已经登记视频时，恢复次数上限不应阻断读取事实；次数上限仅阻止再次渲染。开始耗时媒体工作前新增制作依据和基准版本复核，登记时仍再次校验。

测试环境准备时取消了下载依赖较慢的临时镜像构建，改为复用 S2 镜像。首次复制 Maven 目录包含基础镜像中不存在的目录，构建失败；删除无效 COPY 后 Java/Maven 自检和后续测试通过。以上不是业务测试结果，未修改作者运行环境或依赖版本。

未进行完整 Spring Boot 部署、浏览器入口、自动蓝图、供应商试映或真实阿里云 OSS 验收。待统一回收的未登记对象不视为已完成清理能力。

## 测试资源清理

Testcontainers 创建的 MySQL/RustFS 已随测试结束删除，本轮测试容器和测试镜像标签已清理。结束检查仅见作者原有两个应用容器，本轮没有操作它们。

自动审批两次以 `blocked by policy` 拒绝清理本轮临时目录 `E:\linkAgent\linkAgent\.tmp\s3c-20261020`；一次使用已校验的变量路径，一次改为明确的绝对字面量。未继续改用其他删除工具规避限制。Dockerfile、暂存归档和解包快照因此留在本机，未提交，需作者手动删除该目录。没有残留本轮启动的进程或测试数据库。
