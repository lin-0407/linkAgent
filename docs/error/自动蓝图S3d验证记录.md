# 自动蓝图 S3d 验证记录

日期：2026-10-20。验证对象为暂存源码树 `8988caef21e103239896186fb84f6a2c4bd11a67`；后续仅补文档，未修改已测业务源码。没有部署或发起付费调用。

## 最终结果

334 项回归通过，0 失败、0 错误、0 跳过，耗时 2 分 58 秒。Java 21 编译 573 个主源码与 126 个测试源码；其中 90 项使用真实隔离 MySQL 8.4。相较 S3c 的 322 项增加 12 项，旧结果与本轮首轮结果不重复累加。

实际日志：

```text
S3D_NATIVE_CANCEL_WITHOUT_FAKE_STEPS_OK stoppedMediaProcesses=2
S3D_NATIVE_AUTO_BLUEPRINT_STORAGE_RECOVERY_OK durationMs=84167 files=8 autoSteps=6 generation=2
Tests run: 90, Failures: 0, Errors: 0, Skipped: 0
Tests run: 334, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

## 命令与环境

独立容器内执行：

```text
mvn -B -Dstyle.color=never -Dtest=FixedProjectJobFilesTest,FixedProjectWorkerTest,GeneratedDraftContentHashTest,GeneratedDraftServiceTest,FixedProjectRenderServiceNativeTest,RenderArtifactVerifierTest,MediaVersionPersistenceIntegrationTest,MediaUploadServiceTest,CreatorMediaWorkflowGateServiceTest,ProductionPlanGateServiceTest,MediaDeletionServiceTest,CreatorBilibiliServiceTest,ProductionPlanPersistenceServiceTest,CreatorPublishedVideoContextServiceTest,CreatorFeedbackServiceTest,CreatorReportServiceTest,PhaseZeroTransactionBoundaryTest,PhaseZeroStaleResultTest,CreationWorkspaceControllerTest,CreationRuntimeControllerTest,MediaProcessingServiceTest,PreflightReviewServiceTest,AgentExecutorCacheReplayTest,AgentExecutorRetainedTokenBudgetTest,AgentExecutorToolHistoryTest,SummaryMemoryTest,SummaryMemoryGuardTest,SummaryReviewerTest,RedisShortTermMemoryStoreSerializationTest,StrictFunctionCallingClientTest,LLMServiceThinkingRequestTest test
```

测试镜像复用已有 S2 Python/FFmpeg/中文字体，以及本机已有 Java 21/Maven/依赖缓存。暂存树通过 PowerShell 7.6 的原生二进制管道 `git archive | docker exec -i ... tar` 进入容器，当前快照的 renderer 随后复制至 `/opt/renderer`，不使用镜像中的旧源码。源样例只读挂载，编译和媒体输出均在测试容器内。

容器限制 2 CPU、2GiB、256 个进程，Testcontainers 分别新建 MySQL 和 RustFS。使用的环境开关：

```text
LINKAGENT_RENDERER_NATIVE_TESTS=true
LINKAGENT_GENERATED_NATIVE=true
LINKAGENT_RENDERER_MODULE_ROOT=/opt/renderer
LINKAGENT_RENDERER_SAMPLE_ROOT=/spoken
LINKAGENT_RENDER_TEST_SECRET=dummy-not-a-credential
TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal
TESTCONTAINERS_RYUK_DISABLED=true
```

虚拟 secret 只用于子进程环境隔离测试，不是真实密钥。没有访问作者的业务数据库或媒体库。

## 行为证据

- 原子受理：专属计划、六步绑定和整片运行同时成功或回滚；预算不足、旧修订、缺少确认、显式跳过、确认后换方案/会话均被拒绝。
- 幂等和兼容：重复受理返回同一运行；同键改变时长被拒绝；旧视频、旧试映报告和原人工步骤保留。
- 真实数据库中的证据门禁：缺本地证据不能保存步骤，缺保存证据不能登记；相同证据重放不增加步骤版本；取消和旧租约不能补写；新代数不能复用旧尝试的本地证据。
- 实际 Spring 调度：从新自动蓝图起步，仅 APPROVED 在受理时完成，其余步骤未预先勾选。真实 Python/FFmpeg 渲染 84.167 秒中文视频，Java 验收后写入本地证据，八个 RustFS 私有对象完成 PUT/HEAD/GET 内容核验后完成六步并登记 UPLOADED。
- 实际恢复：移走本测试工作目录后，以第二执行代数恢复同一精确版本，仍只有一个视频版本、八个对象；原 FFprobe 得到真实时长，发布门禁继续拒绝未完成试映的视频，随后删除八个对象。
- 实际取消：观察运行中的 Python/FFmpeg 两个进程，取消后两者退出，没有上传/登记，也没有把未执行步骤写成完成。

数据库协议用例中的工程文件清单为夹具，上传失败、回调顺序等分支使用协议替身；真实媒体证据来自上述原生开关启用的调度、存储、探测和取消用例。发布确认仍通过数据库夹具准备，不能称为真实页面确认或完整产品验收。

## 首轮问题与自检

首轮定向命令为 `mvn -B -Dstyle.color=never -Dtest=GeneratedDraftServiceTest,MediaVersionPersistenceIntegrationTest test`，97 项中 96 项通过、1 项测试构造错误：过期方案用例直接插入同会话第二份方案，违反 `uk_suggestion_task_session`。改为与业务 `upsert` 相同的更新方案标识方式，验证旧确认不能继续使用；随后上述完整 334 项回归通过。

自检确认：本地证据只记录实际文件事实，不把预分配对象键当作已经上传；最后一步使用真实存储验证结果，未把完整试映、投稿文案或发布包伪装为制作完成。

## 未验证与清理

尚未验证完整 Spring Boot 部署、浏览器入口、自动媒体子任务、完整供应商试映、真实云 OSS 或生视频模型调用。`executionAvailable=false` 保留，未改作者当前 Schema、端口或服务。新 AI 生成入口仅有 [补充设计](../develop/AI视频生成制作方式补充方案.md)，范围与首家供应商待确认。

Testcontainers 的 MySQL/RustFS 在测试结束后已删除，本轮带 `creation-s3d-test` 标签的测试容器和镜像已按精确身份移除。结束检查只见作者原有两个运行中的应用容器，本轮未操作它们。没有创建新的宿主归档/解包目录；前轮因自动审批拒绝删除而留下的 `.tmp/s3c-20261020` 沿用 [S3c 清理记录](固定工程后台执行S3c验证记录.md)，本轮未重试或绕过限制。
