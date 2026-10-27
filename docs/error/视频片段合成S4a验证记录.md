# 视频片段合成 S4a 验证记录

日期：2026-10-20。最终源码树 `cfae939ea3fc7452fe5c5167eaf9f868dff0b8be`。功能范围见 [开发说明](../develop/视频自动制作-S4a视频片段合成基础.md)；没有部署、付费调用或作者业务数据操作。

## 最终结果

- Python：58 项通过，耗时 25.597 秒；包含原有 30 项渲染测试与新增 28 项片段测试，实际 FFmpeg 编码、解码和声音断言均执行。
- Java：342 项通过，0 失败、0 错误、0 跳过，最终耗时 2 分 43 秒。573 个主源码和 126 个测试源码编译通过；其中 91 项使用真实隔离 MySQL。
- 两种输入分别由真实 Spring 调度制作 84.167 秒中文视频，完成六步自动蓝图、RustFS 私有存储、精确版本登记、第二代数同版恢复、原 FFprobe 与删除。

实际关键日志：

```text
Ran 58 tests in 25.597s
OK
S3D_NATIVE_CANCEL_WITHOUT_FAKE_STEPS_OK stoppedMediaProcesses=2
V2_NATIVE_BLUEPRINT_STORAGE_RECOVERY_OK schemaVersion=1 durationMs=84167 files=8 autoSteps=6 generation=2
V2_NATIVE_BLUEPRINT_STORAGE_RECOVERY_OK schemaVersion=2 durationMs=84167 files=8 autoSteps=6 generation=2
Tests run: 91, Failures: 0, Errors: 0, Skipped: 0
Tests run: 342, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

最终计数不累加早期试跑。Java 的新增 8 项包括 4 项清单协议、1 项 V2 文件冻结、2 项原生 V2 声音/静音验收及 1 项真实数据库/存储 V2 参数化案例。

## 实际命令与隔离

Python 在复用的 S2 镜像中只读挂载当前 renderer，断网、512MiB、2 CPU、128 进程限制：

```text
python -m unittest discover -s tests -v
```

Java 在本轮新建镜像内执行，复用已有 Python/FFmpeg/中文字体和本机缓存的 Java 21/Maven/依赖；2GiB、2 CPU、256 进程。没有修改作者服务或依赖版本。

```text
mvn -B -Dstyle.color=never -Dtest=FixedProjectJobFilesTest,FixedProjectWorkerTest,GeneratedDraftContentHashTest,GeneratedDraftServiceTest,FixedProjectRenderServiceNativeTest,RenderArtifactVerifierTest,MediaVersionPersistenceIntegrationTest,MediaUploadServiceTest,CreatorMediaWorkflowGateServiceTest,ProductionPlanGateServiceTest,MediaDeletionServiceTest,CreatorBilibiliServiceTest,ProductionPlanPersistenceServiceTest,CreatorPublishedVideoContextServiceTest,CreatorFeedbackServiceTest,CreatorReportServiceTest,PhaseZeroTransactionBoundaryTest,PhaseZeroStaleResultTest,CreationWorkspaceControllerTest,CreationRuntimeControllerTest,MediaProcessingServiceTest,PreflightReviewServiceTest,AgentExecutorCacheReplayTest,AgentExecutorRetainedTokenBudgetTest,AgentExecutorToolHistoryTest,SummaryMemoryTest,SummaryMemoryGuardTest,SummaryReviewerTest,RedisShortTermMemoryStoreSerializationTest,StrictFunctionCallingClientTest,LLMServiceThinkingRequestTest test
```

启用原生开关：

```text
LINKAGENT_RENDERER_NATIVE_TESTS=true
LINKAGENT_GENERATED_NATIVE=true
LINKAGENT_RENDERER_MODULE_ROOT=/opt/renderer
LINKAGENT_RENDERER_SAMPLE_ROOT=/spoken
LINKAGENT_RENDER_TEST_SECRET=dummy-not-a-credential
TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal
TESTCONTAINERS_RYUK_DISABLED=true
```

只有中文样例源素材只读挂载。源码通过 `git archive | docker exec -i ... tar` 的原生二进制管道进入容器，不创建宿主临时归档。首次 Java 快照为 `b4ab66ad410b7c103c9830b93a7c3ad91373b18f`，342 项通过；自检补齐原声实际区间后，将最终树的 renderer 更新至 `/opt/renderer` 重跑完整 342 项，后端源码与首次树经 `git diff` 核对无差异。

## 观察到的媒体行为

- 自制四秒片段在不同秒呈现红/绿/蓝画面，音频每秒使用不同频率；截取第 1–3 秒后，实际首/尾帧为绿/蓝，音频频谱对应截取区间。0.5–2.5 秒截取另有实际像素断言。
- 原声模式实际含源片段频率；替换旁白只含旁白频率；显式 MIX 同时含两路频率。不是仅断言字段或混音命令拼接正确。
- 竖屏 CONTAIN 的实际边缘留黑，COVER 铺满；旋转元数据改变实际显示方向。带非零视频时间原点的片段仍得到正确画面和原声。
- 原声晚于画面 250ms 时，输出保留开头静音及后续声音。音轨存在但所截区间没有原声时拒绝；把原声字幕放在音轨开始前的空白段也拒绝。
- 明确静音产生零峰值并通过 Java V2 验收；选了原声却没有音轨不假成功。没有对白字幕时只输出空 SRT，不自动造字幕。
- 实际编码器与视频解码器同时运行时发送取消，两者均退出且没有成功清单。原有图文超时/取消/完整解码用例继续通过。
- 真实数据库案例的 V2 工程，在原中文工程首场景加入本轮生成的视频片段、保留实际中文旁白，其余场景复用原图文；八个对象和精确版本事实可核对。V1 案例同时保留，均未预先完成系统蓝图步骤。

其他清单字段反例、失租和费用等测试包含协议替身；不将它们单独描述为生产故障注入。发布确认通过数据库夹具建立，不代表实际页面确认已验收。

## 自检与早期问题

原有加新增首批 50 项通过。补测非有限增益时，测试直接调用禁止 Infinity 的摘要函数，导致 56 项试跑中 1 项夹具构造错误；改为直接写入非法 JSON 验证解析入口拒绝，56 项通过。之后自检补齐实际音轨覆盖及原声字幕范围，最终 58 项与 342 项均通过。没有把早期失败或后续重复执行累加为更多通过项。

片段解码使用同一个声画时间原点。依据 [FFmpeg 官方滤镜文档](https://ffmpeg.org/ffmpeg-filters.html#trim)，trim/atrim 后需显式处理时间戳；本实现保留相对选区的原声延迟，并在实际测试中核对，不分别把声画无条件归零。工具输出有界，像素比例规范化的中间画面也受尺寸限制。

## 未验证与清理

未验证浏览器素材上传/剪辑、BGM、ASR、VFR 特殊拍摄素材、HDR 转换、V2 的专项 1080p 片段效果、完整 Spring Boot 部署、自动完整试映、样片业务运行/下载或视频模型接入。没有云 OSS 或真实供应商费用验收。`executionAvailable=false` 保留，未增加公开制作承诺。

所有本轮 `creation-v2-test` 标签的五个容器和新建测试镜像均已按已核对身份删除；Testcontainers 创建的 MySQL/RustFS 已在测试结束时删除。最后仅见作者原有两个运行应用，本轮未操作它们；没有新增宿主输出目录或后台进程。前轮 `.tmp/s3c-20261020` 的审批拒绝清理记录仍保留，本轮未尝试绕过删除限制。
