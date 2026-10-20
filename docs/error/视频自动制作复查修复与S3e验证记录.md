# 视频自动制作复查修复与 S3e 验证记录

日期：2026-10-20。承接 [整体复查](视频自动制作整体复查20261020.md)，实现见 [S3e](../develop/视频自动制作-S3e自动原片探测.md)，内部使用边界见 [自动探测说明](../reference/系统成片自动原片探测说明.md)。

## 本轮结果

7 项复查问题均已修改代码。R1/R2/R4 的反例固化为真实 MySQL 回归；R3/R6/R7 有实际 Java/Python/FFmpeg 验证。R5 只补上传状态恢复分支的操作代数检查，仍仅静态核对，没有浏览器验收。

继续完成 S3e：固定工程 worker 登记系统成片后，自动对该运行精确拥有的私有原片执行 FFprobe，保存实际元信息并进入 READY_FOR_REVIEW。FULL 仍等待该版本的完整媒体依赖，没有把原片探测当作完整试映。公开执行入口仍为 `executionAvailable=false`。

另一个 agent 正在修改前端。本轮前端只改 `frontend/src/composables/creator/useMediaUpload.ts` 的两行保护，不纳入其页面、样式、API 整理、截图和根 README 改动。

## 最终验证

| 范围 | 实际结果 | 说明 |
| --- | --- | --- |
| 后端选定的 38 个套件 | 422 项，0 失败、0 错误、0 跳过，3 分 40 秒 | 包含原制作、媒体、发布门禁及相关 Agent/记忆回归，不代表仓库全部测试 |
| 其中真实隔离 MySQL 集成 | 116 项，全部通过 | 真正的 mapper、事务、行锁和服务，不是 mock 数据库 |
| 渲染器完整 unittest discover | 68 项，全部通过，36.651 秒 | 最终源码及渲染器 0.2.1，实际 FFmpeg |
| 跨语言小数原生测试 | 4 项，全部通过 | Java 冻结/尝试重写后交给 Python 编码，含 -0.0001、-0.000001、-0.1、-0.0 |
| 探测停止原生测试 | 4 项，全部通过 | 3 项真正 FFprobe 的取消/中断/超时；1 项包装进程退出后已观察子进程持有输出管道的回收测试 |

最终后端测试副本由工作区实际字节流复制，不是仅打包 HEAD。复制范围为 `git ls-files -- backend renderer`，730 个文件，按路径排序后逐个累加 UTF-8 路径、NUL、文件字节的 SHA-256：

```text
5a9dae21ea7e6a4fc2c3be56e780839238a855fdb821e6a30b041425b69415ea
```

回归结束后重新计算，摘要一致。该摘要只标记后端/渲染源码快照，不包含并行修改的前端、本文档或外部配音样本。

早期七项修复基线为 384 项通过；S3e 首轮为 154 项通过。它们是重叠的快照，不能相加成总测试量。最终结论以 422 项与 68 项为准。

## 实际观察到的业务行为

- 有活动运行或 RESERVED/UNKNOWN 费用时删除任务返回冲突；结算并结束运行后可正常删除。受理与删除并发的三轮测试中，每轮只有一方成功，没有孤儿运行。
- 取消或失败后，仅未完成且未登记成片的专属自动蓝图失效。原来有已完成人工蓝图时，可以恢复该依据上传自己的成片；首次任务没有人工依据时仍须建立依据，不伪造自动步骤完成。
- 自动工程附加会按该运行精确蓝图检查目标时长，24/25/30 fps 的一帧容差和超限反例均验证；旧人工计划继续保持原语义。
- 非有限数在工程序列化前拒绝；Java/Python 共用浮点摘要 token，保留整数与浮点的区别，FloatNode 按实际 JSON round-trip 后的数值计算。旧浮点工程需重新准备及授权，纯整数摘要保持不变。
- 视频元数据多行/表情标题可实际渲染；真实字幕仍执行排版和字形检查。极端比例、旋转、SAR 的 COVER/CONTAIN 合成在 512MiB 限额下通过，没有先生成巨大中间画面。
- 自动探测只处理本运行 REGISTERED 的精确视频。READY 恢复直接复用；旧代数的迟到成功和失败均拒绝，活动运行期间手工探测与懒恢复不能抢占该版本，其他人工版本保持原处理方式。
- 真实 MySQL 中用触发器延迟元信息写入，使租约在事务中途到期；最终数据库时间检查拒绝提交，READY 状态和元信息整体回滚。触发器在 finally 中删除。
- 取消接管会清理本运行旧代数的自动探测 token，不清理人工 token。真实 FFprobe 取消、中断和超时均停止了该测试进程；包装器测试仅覆盖已观察到的后代，Linux 孤儿僵尸进程可能等待容器 init 回收，不宣称追踪任意瞬间派生进程。

两条真实原生链路均由 Spring 调度：V1 与 V2 各制作 84.167 秒中文工程，完成八对象的私有 RustFS 存储、六个自动蓝图步骤、版本登记及自动原片探测。删除原工作目录后，新代数恢复同一版本；已经 READY 时没有重复签名或探测，完整试映门禁仍然阻止后续发布绑定，最后删除该版本及八对象。

```text
S3E_NATIVE_AUTOPROBE_STORAGE_RECOVERY_OK schemaVersion=1 durationMs=84167 files=8 autoSteps=6 generation=2 sourceProbeCalls=1
S3E_NATIVE_AUTOPROBE_STORAGE_RECOVERY_OK schemaVersion=2 durationMs=84167 files=8 autoSteps=6 generation=2 sourceProbeCalls=1
S3E_NATIVE_PROBE_STOP_OK mode=CANCEL stopped=1
S3E_NATIVE_PROBE_STOP_OK mode=INTERRUPT stopped=1
S3E_NATIVE_PROBE_STOP_OK mode=TIMEOUT stopped=1
```

`sourceProbeCalls=1` 来自签名调用计数与 READY 复用路径，不是 HTTP GET 次数。测试的发布方案确认属于明确的数据库夹具，不是用户在浏览器中实际确认的证据。

## 实际命令与环境

后端使用本轮独立 Java 21 容器、2GiB/2 CPU/256 pids，Testcontainers MySQL 8.4 与临时 RustFS。既有中文配音样本只读挂载为 `/spoken`；没有读作者的云存储密钥或真实模型配置。以下为容器内实际命令，工作目录 `/verification/backend`：

```bash
export LINKAGENT_RENDERER_NATIVE_TESTS=true
export LINKAGENT_GENERATED_NATIVE=true
export LINKAGENT_RENDERER_MODULE_ROOT=/verification/renderer
export LINKAGENT_RENDERER_SAMPLE_ROOT=/spoken
export LINKAGENT_RENDER_TEST_SECRET=dummy-not-a-credential
export TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal
export TESTCONTAINERS_RYUK_DISABLED=true
mvn -o -B -Dstyle.color=never -Dtest=FixedProjectJobFilesTest,FixedProjectWorkerTest,GeneratedDraftContentHashTest,GeneratedDraftServiceTest,FixedProjectRenderServiceNativeTest,FixedProjectDecimalNativeTest,GeneratedDraftProbeServiceTest,GeneratedDraftProbePersistenceServiceTest,FfprobeMediaProbeServiceTest,DraftVideoProbeServiceTest,FfprobeMediaProbeCancellationNativeTest,MediaVersionPersistenceIntegrationTest,RenderArtifactVerifierTest,MediaUploadServiceTest,CreatorMediaWorkflowGateServiceTest,ProductionPlanGateServiceTest,MediaDeletionServiceTest,CreatorBilibiliServiceTest,ProductionPlanPersistenceServiceTest,CreatorPublishedVideoContextServiceTest,CreatorFeedbackServiceTest,CreatorReportServiceTest,CreatorTaskServiceTest,PhaseZeroTransactionBoundaryTest,PhaseZeroStaleResultTest,CreationWorkspaceControllerTest,CreationRuntimeControllerTest,MediaProcessingServiceTest,PreflightReviewServiceTest,AgentExecutorCacheReplayTest,AgentExecutorRetainedTokenBudgetTest,AgentExecutorToolHistoryTest,SummaryMemoryTest,SummaryMemoryGuardTest,SummaryReviewerTest,RedisShortTermMemoryStoreSerializationTest,StrictFunctionCallingClientTest,LLMServiceThinkingRequestTest test
```

渲染器为最终源码只读挂载，断网、512MiB/2 CPU/128 pids 的独立容器，在 renderer 目录执行：

```bash
PYTHONDONTWRITEBYTECODE=1 python -m unittest discover -s tests
```

同时执行 `git diff --check`。没有运行前端命令、前端 type-check、完整 Spring Boot、浏览器、云 OSS、付费模型或生产部署。尚未接通自动预处理/完整试映、发布包、费用全面归并、素材上传页面、样片业务与自然语言局部修改。

## 早期失败与修正

第一次复制方案尝试在缓存镜像里使用未安装的 git。中止该容器，改为从宿主读取实际文件并流式发送 tar；误启动的旧快照没有计入测试成功。

首个较大快照的两条原生 84 秒测试返回 QUALITY_FAILED/INVALID_INPUT。定位到测试夹具硬编码 `/opt/renderer`，错误加载镜像内预装的 0.1.0 模块，而本次源码在 `/verification/renderer`。两处测试改为读取 `LINKAGENT_RENDERER_MODULE_ROOT`；先重跑两条原生测试，再完成整轮回归，没有跳过失败用例或放宽生产验收。首次缺少 Surefire provider 时下载测试依赖，最终回归使用离线 Maven；这不是付费模型调用。

## 清理

最终回归正常结束后，Testcontainers 容器均已退出并销毁。核对 ID 与 `linkagent.scope=creation-fix-test` 标签后，删除本轮 `linkagent-creation-fix-backend-20261020` 容器及 `linkagent-creation-fix-base:20261020` 镜像；最后按标签查询容器、镜像和 Testcontainers 均为空。

本轮创建的 HTTP 测试服务器、探测与编码进程已结束；测试数据库、存储对象、工作目录和容器内报告随测试或容器删除。没有创建宿主临时媒体、归档或前端服务，没有操作旧 `.tmp/s3c-20261020`。最终运行列表仅有作者原有的 `dafeiyu-desktop`、`dafeiyu-napcat`，未操作它们或其他原有容器。
