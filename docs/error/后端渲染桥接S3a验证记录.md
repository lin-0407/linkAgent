# 后端渲染桥接 S3a 验证记录

日期：2026-10-20。范围为 Java 调用 S2 渲染器并核验本地产物；没有供应商付费请求、OSS 写入、业务部署或前端变更。

## 验证对象与环境

测试使用 Git 暂存树 `d83082004367f44ce92c64f2fb731d274976ba87` 导出的归档，而非混入本机忽略文件的工作目录。该树包含本轮 Java 实现与测试，后续只补充文档。

一次性测试镜像 `linkagent-renderer-java-test:s3a-20261020` 在 S2 镜像上拷入已有 Java 21、Maven 和本地依赖缓存；未安装或更改宿主机环境。构建用 Dockerfile 为：

```dockerfile
FROM linkagent-renderer:s2-20261020
COPY --from=linkagent-first-video-final-compiled:20261018 /opt/java/openjdk /opt/java/openjdk
COPY --from=linkagent-first-video-final-compiled:20261018 /usr/share/maven /usr/share/maven
COPY --from=linkagent-first-video-final-compiled:20261018 /root/.m2 /root/.m2
ENV JAVA_HOME=/opt/java/openjdk PATH=/opt/java/openjdk/bin:/usr/share/maven/bin:/usr/local/bin:/usr/local/sbin:/usr/sbin:/usr/bin:/sbin:/bin
ENTRYPOINT []
```

测试容器限制 2 CPU、2GiB 内存及 256 个进程。只读挂载暂存归档和已有 `export/renderer-s2-20261020/rest` 工程；真实 MySQL 由 Testcontainers 新建隔离容器。Java 测试生成的输入和视频均位于容器的 JUnit 临时目录，结束自动删除。

NativeTest 显式设置：

```text
LINKAGENT_RENDERER_NATIVE_TESTS=true
LINKAGENT_RENDERER_MODULE_ROOT=/opt/renderer
LINKAGENT_RENDERER_SAMPLE_ROOT=/spoken
LINKAGENT_RENDER_TEST_SECRET=dummy-not-a-credential
TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal
TESTCONTAINERS_RYUK_DISABLED=true
```

普通开发环境未提供真实依赖时 NativeTest 会跳过，因此不能把默认 Maven 结果当作本轮真实渲染验收。此次明确启用，16 项全部实际执行。

## 实际命令与观察

从归档解包，在 `backend` 运行：

```text
mvn -B -Dstyle.color=never -Dtest=FixedProjectRenderServiceNativeTest,RenderArtifactVerifierTest,MediaVersionPersistenceIntegrationTest,MediaUploadServiceTest,CreatorMediaWorkflowGateServiceTest,ProductionPlanGateServiceTest,MediaDeletionServiceTest,CreatorBilibiliServiceTest,ProductionPlanPersistenceServiceTest,CreatorPublishedVideoContextServiceTest,CreatorFeedbackServiceTest,CreatorReportServiceTest,PhaseZeroTransactionBoundaryTest,PhaseZeroStaleResultTest,CreationWorkspaceControllerTest,CreationRuntimeControllerTest,MediaProcessingServiceTest,PreflightReviewServiceTest,AgentExecutorCacheReplayTest,AgentExecutorRetainedTokenBudgetTest,AgentExecutorToolHistoryTest,SummaryMemoryTest,SummaryMemoryGuardTest,SummaryReviewerTest,RedisShortTermMemoryStoreSerializationTest,StrictFunctionCallingClientTest,LLMServiceThinkingRequestTest test
```

新增两组：

- `FixedProjectRenderServiceNativeTest`：16 项通过，0 跳过；真实生成 2 秒协议测试视频，并由 Java 再渲染、接收已有的 84.167 秒中文配音工程，2020 帧和八个文件验收通过。取消、超时、编码失败、缺字体、线程中断、环境隔离、输出超限、缺产物的零退出和执行中工程变化均被拒绝或终止。本组包含受控假模块的进程边界反例，不将其算成真实视频生成。
- `RenderArtifactVerifierTest`：31 项通过；用占位文件验证身份、摘要、完整性、媒体事实、软链接和 JSON 边界，不冒充真实媒体播放或供应商链路。

最终 `BUILD SUCCESS`：269 项全部通过，0 失败、0 错误、0 跳过；包括上述 47 项和原有 222 项回归，其中 55 项使用真实隔离 MySQL。主源码 555 个、测试源码 122 个编译通过。整体耗时 3 分 21 秒，NativeTest 47.20 秒。模型和存储相关旧测试仍使用替身，没有实际供应商或 OSS 调用。

测试结束后核对最终 Java 实现与测试和上述受测树没有差异，仅补文档。一次性测试容器、隔离 MySQL、测试镜像标签、归档和临时 Dockerfile 均已清理；原有 `dafeiyu-napcat` 保持运行，S2 样片保留。

## 遇到的问题

首次离线测试已通过 555 个主源码、122 个测试源码的编译，但镜像缓存缺少 Surefire 3.5.4 的 JUnit Platform Provider，`mvn -o` 在执行测试前失败。随后允许 Maven 下载所缺测试依赖并重新执行；未修改业务依赖版本，也未把第一次编译成功算作测试通过。

自检补充处理了时长差值的整数溢出反例，并按大小写无关方式保留 Windows 系统环境变量，避免丢失 `Path`。

## 验收边界

仅证明 Java 到本地离线渲染器的真实接通和产物校验。未验证持久化 worker 重启恢复、真实 OSS 上传、版本注册、现有媒体预处理或付费完整试映；没有标记 FULL 运行完成。当前默认不开启，也没有会排队却无人消费的新接口。
