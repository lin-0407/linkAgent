# 系统成片保存 S3b 验证记录

日期：2026-10-20。对象是 Git 暂存树导出的核心源码，测试不混入本机被忽略的评测模块。

## 验证方法

使用一次性 Java 21/Maven/Python/FFmpeg/中文字体容器，限制 2 CPU、2GiB 内存和 256 个进程。Testcontainers 创建真实隔离 MySQL 8.4；新增真实链路额外创建一次性 RustFS 私有 S3 兼容服务，不使用作者存储或已有业务数据。

除 S3a 的原有环境变量外显式设置：

```text
LINKAGENT_GENERATED_NATIVE=true
LINKAGENT_RENDERER_NATIVE_TESTS=true
LINKAGENT_RENDERER_MODULE_ROOT=/opt/renderer
LINKAGENT_RENDERER_SAMPLE_ROOT=/spoken
TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal
TESTCONTAINERS_RYUK_DISABLED=true
```

不设置真实集成开关时相关测试会跳过，因此默认 Maven 执行不能算成本次真实链路验收。

主要测试：

- `MediaVersionPersistenceIntegrationTest`：原 55 项加本轮整片授权、产物清单、幂等、回滚、并发、取消/失租/接管、独立版本与删除测试。确认记录和步骤为明确标注的数据库夹具，不代表验收了真实确认页面或自动蓝图执行。
- `realRenderedVideoSurvivesPrivateStorageRegistrationProbeAndDeletion`：基于已有中文配音工程，用当前运行身份重新渲染真实 84 秒视频；通过现有 S3 客户端上传/回读全部产物，真实 MySQL 登记，原 FFprobe 读取私有短签对象，再真实删除所有登记对象。完整试映没有完成，发布门禁应继续阻断。
- `GeneratedDraftServiceTest`：存储协议替身验证错误长度、同长篡改、取消、存储失败、完整往返要求和不依赖本地目录的恢复；不冒充真实媒体或存储链路。
- `GeneratedDraftContentHashTest`：运行身份变更、内容变更、工程 256KiB 与控制请求 64KiB 边界。
- 回归 S3a 实际渲染和原版本、运行预算、上传、绑定、门禁、记忆等核心测试。

## 实际命令与结果

首次修正 Mapper 和测试镜像后，从暂存树 `d20b80bb2c2acec1c60d0a9c65eeb3da57e37704` 解包，在 backend 执行：

```text
mvn -B -Dstyle.color=never -Dtest=GeneratedDraftServiceTest,FixedProjectRenderServiceNativeTest,RenderArtifactVerifierTest,MediaVersionPersistenceIntegrationTest,MediaUploadServiceTest,CreatorMediaWorkflowGateServiceTest,ProductionPlanGateServiceTest,MediaDeletionServiceTest,CreatorBilibiliServiceTest,ProductionPlanPersistenceServiceTest,CreatorPublishedVideoContextServiceTest,CreatorFeedbackServiceTest,CreatorReportServiceTest,PhaseZeroTransactionBoundaryTest,PhaseZeroStaleResultTest,CreationWorkspaceControllerTest,CreationRuntimeControllerTest,MediaProcessingServiceTest,PreflightReviewServiceTest,AgentExecutorCacheReplayTest,AgentExecutorRetainedTokenBudgetTest,AgentExecutorToolHistoryTest,SummaryMemoryTest,SummaryMemoryGuardTest,SummaryReviewerTest,RedisShortTermMemoryStoreSerializationTest,StrictFunctionCallingClientTest,LLMServiceThinkingRequestTest test
```

`BUILD SUCCESS`：290 项通过，0 失败、0 错误、0 跳过，耗时 2 分 42 秒。563 个主源码、123 个测试源码编译通过。真实 MySQL 集成组 69 项通过，含上述真实渲染/私有 S3 兼容存储/FFprobe/删除链路；S3a NativeTest 16 项全部执行且通过。新服务的 7 项失败分支测试使用协议替身。

实际日志输出 `S3B_NATIVE_STORAGE_PROBE_DELETE_OK durationMs=84167 files=8`，读取私有视频对象获得实际媒体参数；试映门禁仍返回未就绪。这里的私有对象存储为真实 RustFS，不能表述为阿里云 OSS 联调已通过。

随后修正工程摘要的大小限制并新增 3 项针对性测试。最终源码树 `fd0200bfc3a5a75ddcff7e847138e3fa7e78c801` 相比上述受测树仅变更摘要字节上限及其测试，在断网隔离容器运行：

```text
mvn -o -B -Dstyle.color=never -Dtest=GeneratedDraftContentHashTest test
```

该组 `BUILD SUCCESS`：3 项通过，0 失败、0 错误、0 跳过，耗时 34.198 秒，563 个主源码和 124 个测试源码编译通过。最终统计为“290 项回归 + 3 项针对性补测”，没有把两次执行写成同一轮完整测试。

最终实现和测试与 `fd0200bfc3a5a75ddcff7e847138e3fa7e78c801` 无差异，仅补充文档。测试容器、隔离 MySQL/RustFS、测试镜像标签、暂存归档、日志、临时脚本及 Maven 测试缓存副本均已清理；原有 `dafeiyu-napcat` 保持运行，原 S2 镜像与样片未修改。

## 首轮发现的问题及处理

1. MyBatis 按方法名生成映射 ID，不能用 Java 同名重载分别表示查询和更新。首次新增 Mapper 导致数据库测试初始化失败，已将更新改为独立的 `markRegistered`。
2. 本地 `linkagent-renderer:s2-20261020` 标签实际指向旧渲染源码，仍含会让封装器提前结束的 `-frames:v` 输出参数。首轮真实视频测试因此触发 QUALITY_FAILED。仓库中的 S2 源码已有修复；本轮测试镜像显式 COPY 当前模块，未修改原有镜像标签。当前仓库 engine.py 的 SHA-256 为 `fa5def840d5dd0d13939f8044561af1488f960d1d6e1eeb40c18e05f0701bd24`。
3. 自检发现工程摘要复用了运行请求的 64KiB 限制，导致部分 S2 合法工程渲染后无法保存。修正为工程沿用 256KiB，控制请求保持 64KiB，并增加边界回归。

## 尚未验证

未调用阿里云 OSS 或真实付费供应商，未验收持久化 worker、自动蓝图、完整预处理/试映、发布包或浏览器入口。当前不能对外宣称 FULL 自动制作全链路完成。
