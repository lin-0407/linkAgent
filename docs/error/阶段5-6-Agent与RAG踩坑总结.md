# 阶段5-6 Agent与RAG踩坑总结

本文件将阶段5、阶段6及RAG主题相关17篇踩坑记录按主题分类整合，去重合并后精简为一篇。每条按"问题 -> 原因 -> 解决"三要素记录。

---

## 一、向量库与Embedding

### 1. 第二个VectorStore Bean会打断反馈侧注入

反馈RAG通过`ObjectProvider<VectorStore>.getIfAvailable()`注入向量库，知识库若也注册`VectorStore`类型Bean会导致`NoUniqueBeanDefinitionException`。

原因：知识库使用专用集合与维度，与反馈集合不同，不应共用同一类型Bean。

解决：`KnowledgeVectorStore`内部私有持有`MilvusVectorStore`，不暴露为`VectorStore`类型Bean，从根上消除歧义。

### 2. 自行构建的MilvusVectorStore需手动afterPropertiesSet

`MilvusVectorStore.builder().build()`创建的实例不是Spring托管Bean，`afterPropertiesSet()`不会被自动回调，首次写入时因schema未建立而失败。

原因：`initializeSchema(true)`仅声明需要建集合，实际建集合动作在`afterPropertiesSet()`中。

解决：`build()`后手动调用`store.afterPropertiesSet()`，再标记ready。

### 3. Qwen Embedding单批硬上限为10

Qwen `text-embedding-v3/v4`的OpenAI兼容端点单批硬上限是10，照搬反馈侧25会导致`batch size invalid`。

原因：不同Embedding供应商的批量上限是供应商侧硬约束。

解决：`knowledge.rag.index-batch-size`默认10，索引服务按此分批，并用`Math.max(1, ...)`兜底。

### 4. Embedding维度须与集合维度严格一致

集合按1024维创建后，若Embedding实际输出其他维度（如沿用反馈默认1536），写入会报维度冲突。

原因：维度是两处分别配置时最容易漂移的参数。

解决：`knowledge.rag.embedding-dimension`与`LLM_EMBEDDING_DIMENSIONS`环境变量绑定，使集合维度与Embedding输出维度共用一个真相源。

### 5. EMBEDDING_MODEL_TYPE=none时无EmbeddingModel Bean

演示环境默认`EMBEDDING_MODEL_TYPE=none`，若用构造器硬注入`EmbeddingModel`会导致启动失败。

原因：RAG是可关基础设施，关闭态必须能正常启动。

解决：使用`ObjectProvider<EmbeddingModel>`软注入，配合业务开关与Bean存在双层判断决定是否构建向量库。

### 6. Spring AI Embedding的base-url不应携带compatible-mode路径

Spring AI 1.1.4的OpenAI Embedding使用`RestClient.baseUrl(baseUrl)`加`uri(embeddingsPath)`，若base-url包含compatible-mode路径可能被覆盖导致404。

原因：`base-url`负责协议和域名，`embeddings-path`负责供应商路径，二者需分开。

解决：`base-url`设为`https://dashscope.aliyuncs.com`，`embeddings-path`设为`/compatible-mode/v1/embeddings`。

### 7. Embedding调用需用BeanPostProcessor统一埋点

VectorStore内部会自动触发embedding，仅在部分业务方法里手动统计会漏记内部调用。

原因：业务服务和VectorStore不在同一调用链上。

解决：用`BeanPostProcessor`为容器中的`EmbeddingModel`包装`MeteredEmbeddingModel`，让业务服务和VectorStore共用同一个代理模型。

### 8. 抛弃Spring AI固定schema以支持原生hybrid

Spring AI的`MilvusVectorStore`按固定schema建集合，没有sparse字段与BM25 Function，无法承载dense+BM25原生hybrid。

原因：固定schema封装件装不下sparse+Function。

解决：新增`KnowledgeHybridStore`，内部私有持`MilvusClientV2`并自建schema（含VarChar+中文analyzer、SparseFloatVector+BM25 Function、FloatVector dim=1024），dense需手动用`EmbeddingModel.embed(List)`计算。

### 9. 同一个MilvusServiceClient上建两个集合需各自afterPropertiesSet

子集合向量化需在不破坏父集合的前提下新增child `MilvusVectorStore`。

原因：父集合已正常服务，子集合是附加索引。

解决：`KnowledgeVectorStore`内部再持一个指向子集合的`MilvusVectorStore`，共用同一个`MilvusServiceClient`和`EmbeddingModel`，仅集合名不同，各自`build()`后调用`afterPropertiesSet()`。

### 10. 子集合建库失败必须独立降级

子集合建库失败若让整个`init()`抛错，会把已可用的父检索一起带挂。

原因：子召回是附加召回源，不能连累主线。

解决：父集合try/catch后追加子集合构建，用独立try+独立就绪位`childReady`。父ready不受子建库失败影响，检索仅在`isChildReady()`时叠加子召回。

### 11. v2 SDK的泛型与@SuperBuilder编译期陷阱

自建schema+hybridSearch存在编译期硬约束：analyzerParams的`Map<String,String>`与API要求的`Map<String,Object>`不变不可赋值；`ConnectConfig`和`AnnSearchReq`的`@SuperBuilder`通配符导致类型推断失败。

原因：Java泛型不变性与`@SuperBuilder`通配符是编译期硬约束。

解决：analyzerParams用`Map.<String,Object>of(...)`；`ConnectConfig`用单链`build()`，`AnnSearchReq`用`var`+逐句`set`规避；`createCollection`后显式再`loadCollection`兜底。

### 12. Rerank用compatible-api而非compatible-mode

`qwen3-rerank`走OpenAI兼容端点，路径是`POST .../compatible-api/v1/reranks`，与chat用的`compatible-mode`不同。RestClient的`baseUrl`+前导斜杠URI会触发RFC 3986绝对路径替换，丢失base路径导致404。

原因：DashScope同时存在三套路径命名，易混淆；URI解析的绝对路径替换是标准行为。

解决：`base-url`默认`https://dashscope.aliyuncs.com/compatible-api/v1`，与chat独立配置。拼完整绝对URL后整个交给`.uri(...)`，不设baseUrl。

### 13. gte-rerank已下线，改用qwen3-rerank

`gte-rerank`已于2026-05-30下线，继续使用会调不通。

原因：模型下线需联网核实替代方案。

解决：改用`qwen3-rerank`纯文本模型。

---

## 二、RAG检索与分块

### 1. 查询增强组件的内置prompt是英文

Spring AI的`RewriteQueryTransformer`和`MultiQueryExpander`内置默认prompt是英文，直接用于中文query+中文案例库会劣化召回。

原因：查询增强的产物要喂中文向量检索，扩展文本必须保持中文语义。

解决：两者都显式注入中文`promptTemplate`。HyDE自定义实现本就用中文prompt。

### 2. 自定义promptTemplate占位符变量名必须与框架一致

占位符写错变量名会导致框架渲染时找不到变量而报错，不是静默忽略。

原因：占位符名是框架与模板的契约。

解决：对spring-ai 1.1.4字节码核准占位符名——`RewriteQueryTransformer`用`{query}/{target}`，`MultiQueryExpander`用`{query}/{number}`。

### 3. 查询变换走ChatClient绕过LLMService字符护栏

查询增强若走`LLMService.validatePromptLength`（30000字符护栏）会引入不必要耦合。

原因：增强是检索前的轻量扩展，入参已有更紧的长度约束（`@Size(500)`）。

解决：查询变换组件直接用`ChatClient.Builder`调LLM，绕过字符护栏。`MultiQueryExpander.numberOfQueries`设硬上限（默认3）防检索次数放大。

### 4. 子文档反范式带category/tier

子召回要支持与父召回同款`category`/`tier`过滤，但子表`creator_reference_video_item`没有这两列。

原因：父表这两列导入后极少变更，反范式成本可接受。

解决：索引时JOIN父表带出`v.category`/`v.tier`，写入子向量文档metadata，子召回复用父检索同一个`buildFilter`。

### 5. 子召回提取videoId绝不能回退document.getId()

父侧`extractVideoId`在metadata缺失时回退`document.getId()`（父文档id就是videoId，回退安全）。子文档id是itemId，若复用父侧逻辑会把itemId误当videoId。

原因：父子文档主键语义不同（父=videoId，子=itemId）。

解决：子召回单列`extractChildMetadata`，只读metadata、不回退getId()。

### 6. 子hybrid集合PK=item_id，video_id必须outFields

父hybrid集合PK=video_id，搜索结果直接用`SearchResult.getId()`即可。子hybrid集合PK=item_id，video_id是普通字段。

原因：Milvus搜索默认只返回主键+距离，非主键字段必须显式`outFields`声明。

解决：子集合搜索`HybridSearchReq`必须`outFields("video_id")`，再用`SearchResult.getEntity().get("video_id")`获取。

### 7. 子hybrid重灌源不能看embedding_status

子hybrid是独立集合、drop重建、不共享子表`embedding_*`状态机，若照搬增量索引逻辑会漏掉已被标记INDEXED的条目。

原因：hybrid重灌源必须是全量事实，而非增量待办。

解决：子hybrid用新mapper `listAllItemsForHybrid`（全量未删子条目、不看embedding_status），JOIN父表带category/tier。

### 8. 中块只从原材料确定性拼装，不做LLM新摘要

若让LLM为每个视频生成主题中块，会增加导入成本，也可能产生原材料里没有的结论。

原因：优化目标是召回粒度，不是生成新知识。

解决：中块从标题、简介、标签、亮点摘要和已清洗评论弹幕确定性拼装。

### 9. 中块文档ID不能当videoId

中块向量文档的id是chunkId，不是videoId，若检索时回退`document.getId()`会把chunkId当成videoId。

原因：父块、主题中块、子块三者主键语义不同，回查父表时必须统一使用videoId。

解决：中块召回只从metadata读取videoId，不回退文档id。

### 10. 老数据没有中块需自动补齐

上线前已导入的视频只有父块和子块，直接新增中块索引会找不到待索引数据。

原因：不能要求作者重新采集历史视频。

解决：`/index/chunks/rebuild`在索引前先查询没有中块的视频并补齐主题中块。补齐条件为"缺少可生成的中块"而非"少于三类"硬凑。

### 11. 主题优先检索不能只返回父视频卡片

父块命中只能说明整条视频整体相关，不能说明具体相关点。

原因：创作者需要的是"为什么这个案例值得看"。

解决：先检索主题中块，再用命中的videoId展示视频卡片，matchedTopics只保留当前页卡片对应的主题。

### 12. 中块候选数需大于视频候选数

一个视频可能有多个主题中块，若只检索20个中块文档，去重后可能凑不出20个视频。

原因：刷新4页最多需要20个视频候选。

解决：中块向量检索取60个文档，再收敛成20个唯一视频。

### 13. Rerank需对top20候选池整体精排

如果每次只对当前页5张卡片精排，刷新到第2批时排序口径变成"局部最优"。

原因："换一批"应展示同一组精排结果的后续区间。

解决：先形成top20候选池，对top20做一次rerank，再按page切片。

### 14. Rerank不能只看主题，还要看评论弹幕

主题只能说明视频大概相关，不能说明观众具体反馈。

原因：视频卡片排序应优先反映与当前query相关的真实观众反馈。

解决：topic-search先在top20候选视频范围内按query检索子向量集合，把相关评论/弹幕加入rerank文本。

### 15. 不能用热门评论替代相关评论

点赞高的评论通常代表情绪强或段子好，不一定回答用户问题。

原因：排序依据必须服务当前query，而非评论本身热度。

解决：子向量集合可用时先用query在候选视频内部检索评论/弹幕；子向量不可用时才退回热门代表证据。

### 16. 中块零命中不能直接空结果

中块集合未索引或query更像标题/BV精确查找时，中块向量可能正常返回0条，前端显示空屏。

原因：父表存在且标题可匹配时，不应因中块层未覆盖就阻断。

解决：中块召回异常、零命中或中块命中后父表回查为空时，统一退回SQL质量分兜底。

### 17. SQL兜底不能只做整串LIKE

空格、括号、斜杠等符号差异会使整串LIKE误杀。

原因：兜底检索的职责是"别漏掉显然存在的案例"。

解决：SQL兜底从query提取中英文数字关键词片段，按片段AND匹配标题、简介、亮点摘要和BV号。无可用片段时才退回到整串LIKE。

### 18. 向量metadata过滤后仍需MySQL二次过滤

中块向量文档里的category/tier来自索引时的metadata，后续MySQL事实源可能更新。

原因：最终展示必须以MySQL事实源为准。

解决：回查视频卡片时再次按category/tier过滤。

### 19. hybrid路径暂不混入中块dense集合

若混入中块dense集合，HYBRID mode名称无法说明真实召回路径。

原因：检索模式回显要可信。

解决：中块dense召回只在非hybrid路径启用。中块hybrid作为独立后续切片。

### 20. 前端需暴露主题中块索引状态

前端只有父表、子条目、hybrid索引入口，用户看到父表"已索引"会误以为主题优先检索也可用。

原因：运维界面必须按实际检索依赖展示状态。

解决：索引面板补充"主题中块索引"的状态与重建入口。

---

## 三、Agent编排与Multi-Agent

### 1. 结构化输出json_object不保证字段正确

DeepSeek兼容OpenAI的`response_format=json_object`可约束返回合法JSON，但不会强制包含特定字段。

原因：JSON语法正确不等于业务schema正确。

解决：`LLMService.chatStructured`同时使用`.entity(type)`做强类型解析，解析失败时最多重试3次。

### 2. 结构化内核初期需默认关闭

`AgentExecutor`是通用聊天入口和后续创作Agent化的共同内核，一次性替换默认路径风险太大。

原因：内核级改动不应影响`/api/agent/chat`主线。

解决：新增`agent.kernel.structured.enabled`默认false，关闭时回退文本ReAct；稳定后调整为默认true。

### 3. 不能把发布前优化内部取证写入用户会话记忆

若复用`run()`做任务内部推理，会把内部取证过程写进短期记忆，还可能触发长期记忆抽取。

原因：发布前优化的取证过程不是用户聊天回合。

解决：`runTask()`不读写短期记忆、摘要记忆和长期记忆。

### 4. 结构化系统提示词不再写文本格式约束

文本ReAct的系统提示词要求输出`Thought:`、`Action:`等格式，与JSON schema目标冲突。

原因：结构化路径的格式约束由`BeanOutputConverter`追加的schema指令承担。

解决：结构化系统提示词只说明每步如何在`ReActStep`字段里二选一（调用工具或给最终答案）。

### 5. 文本ReAct格式漂移不能记录成空步骤

LLM未输出`Thought:`时，旧逻辑会先追加空`AgentStep`然后继续解析`Action:`。若`Action:`也不存在，同一轮又追加一条相同编号的空步骤。

原因：这类步骤不是正常推理步骤，而是模型格式漂移的恢复过程。

解决：`run()`和`runTask()`在未解析到`Thought:`时只记录一条带诊断说明的步骤，并立即`continue`进入下一轮重试。

### 6. AUTO路由关键词不能太宽

"优先用Java举例"也包含"先"，会被误判为复杂任务进入PaE编排。

原因：自动模式应优先保持低成本和低打扰。

解决：关键词收窄为"先做/再做/计划/步骤/拆解"等更明确的复杂任务词。

### 7. Multi Agent不能伪装成一次LLM多角色prompt

若在一个prompt中写"你现在扮演Planner、Worker、Synthesizer"，工程上不可替换、不可测试、不可独立扩展。

原因：多Agent的工程价值是模块化和可演化。

解决：`MultiAgentPlanner`、`WorkerAgent`、`AgentAnswerSynthesizer`都是独立Bean，Worker通过`List<WorkerAgent>`自动注入。

### 8. runTask不能被AUTO隐式改行为

发布前优化已依赖`AgentExecutor.runTask(String)`做内部ReAct取证，若阶段6让它默认AUTO会改变链路成本。

原因：公开聊天可自动路由，内部业务链路必须显式选择。

解决：保留`runTask(String)`原语义，新增`runTask(String, AgentExecutionMode)`。

### 9. 并发不能忽略依赖失败

若只按空依赖并发，依赖失败后的后续Worker可能继续运行，产生基于缺失事实的结论。

原因：Worker之间存在事实依赖链。

解决：每轮执行前检查依赖状态，依赖不存在或不是SUCCESS时将后续Worker标记为SKIPPED。

### 10. Direct Worker的结论不能当外部事实

直接推理Worker没有工具观察，其输出本质是模型推断。

原因：Synthesizer需要区分外部事实与模型推断。

解决：将Direct Worker的证据类型标记为`WORKER_REASONING`，Synthesizer prompt明确要求不将其当成外部事实。

### 11. PaE重规划不能覆盖已成功步骤

若让Replanner重新输出完整计划，模型可能把已成功步骤再执行一遍。

原因：Replanner不应影响已完成的步骤。

解决：prompt明确要求只输出剩余步骤，执行器只替换`remainingSteps`。重规划步骤由后端统一重新编号，不信任模型返回的stepId。

### 12. Replanner自身需有降级机制

Replanner自身可能因提示词缺失或结构化输出失败而异常。

原因：重规划是增强能力，不应成为单点。

解决：`AgentReplanner`捕获异常后保留原剩余计划。执行器记录已被重规划替代的失败stepId，计算stopReason时不把这些失败尝试当成最终失败。

### 13. runTask的新增构造参数需全仓搜索

`AgentExecutor`构造器新增`PromptService`后，单测中手动`new AgentExecutor(...)`的地方漏传参数会导致编译失败。

原因：Spring容器可自动注入，但单测手动构造不会自动补参数。

解决：补齐`StubPromptService`，并全仓搜索`new AgentExecutor(`确认所有构造点。

### 14. ThreadLocal不会自动跨异步工具线程

`LlmUsageContext`使用`ThreadLocal`保存任务和步骤上下文，但`ToolExecutor`内部通过`CompletableFuture.supplyAsync`执行工具，工具线程读不到ThreadLocal。

原因：只要模型调用可能发生在异步线程，就不能假设ThreadLocal自动可见。

解决：`LlmUsageContext`增加`restore(...)`，`ToolExecutor`在进入异步工具线程时恢复捕获到的上下文。

### 15. Agent失败不能直接覆盖为成功步骤

若Agent失败后在同一个步骤里执行旧直连LLM并标记成功，过程弹窗会误导用户。

原因：用户需看到真实链路。

解决：Agent失败时标记AGENT_REASONING为失败，新建LLM_CALL回退步骤，回退成功后只把LLM_CALL标记成功。

---

## 四、Prompt模板管理

### 1. 不做缓存更符合热更新目标

每次模型调用都查库看似有开销，但模型调用本身是秒级，按唯一键查一条提示词是毫秒级。

原因：缓存省下的成本很小，却引入缓存刷新问题。

解决：`PromptService.get(key)`每次直接查`llm_prompt_template`，天然支持热更新。

### 2. 数据库是唯一来源，缺key必须响亮报错

若代码里保留默认提示词兜底，会重新变成双来源，出现"数据库改了但实际用了代码默认值"的隐蔽问题。

原因：作者要求不把提示词硬编码在业务代码里。

解决：`PromptService.get(key)`查不到时直接抛`ResponseStatusException`，提示确认`init.sql`是否执行。

### 3. USER提示词从`%s`改为`{varName}`命名占位符

`%s`位置占位符让前端编辑者很难知道每个位置代表什么，且正文出现普通`%`字符会触发格式化异常。

原因：命名占位符更适合可编辑提示词，不依赖参数顺序。

解决：USER模板统一使用`{varName}`命名占位符，由`PromptService.render(key, Map)`做字符串替换。

### 4. Map.of()不能接收null value

`render(key, Map)`调用点大多用`Map.of()`传变量，如果value是`null`会抛`NullPointerException`。

原因：提示词变量是给模型看的业务文本，null应转为可读文本。

解决：调用点传入Map前使用`TextUtil.trimToDefault(...)`把空值归一成"未提供"等明确文本。

### 5. 测试断言不再绑定提示词原文

提示词可热更新后，测试若继续断言原文中的某句话，提示词微调会打红测试。

原因：提示词正文是可调参数，key才是代码契约。

解决：`StubPromptService.get(key)`返回`[test-prompt:key]`，测试只断言key是否正确。

### 6. PowerShell智能引号会造成Java编译期硬错误

个别Java字符串字面量被输入法带成全角智能引号，直接编译失败。

原因：Java字符串字面量只能使用半角双引号。

解决：全仓扫描`.java`确认无残留全角引号。

---

## 五、LLM成本与观测

### 1. token未返回不能写成0

Spring AI或供应商有时不返回usage，若把未知token写成0，前端会误以为无成本。

原因：0和null语义不同。

解决：后端把0或缺失usage统一转成null，前端显示"未返回"。

### 2. ChatClient.entity(...)不暴露完整usage

结构化输出可直接得到目标对象但拿不到完整`ChatResponse`，若为拿token强行改回字符串JSON解析会破坏结构化输出稳定性。

原因：结构化调用和usage获取存在取舍。

解决：结构化调用仍记录真实耗时和调用状态，token保持未知不做估算。

### 3. 统计失败不能影响创作主流程

统计表写入可能因数据库连接或字段长度临时异常失败，若向上抛会中断主业务。

原因：统计是辅助能力，不能成为可用性单点。

解决：`LlmApiUsageService`捕获写入异常后只输出warn日志，主业务继续按原逻辑返回。

### 4. MyBatis聚合对象不使用record

聚合SQL返回下划线别名需映射到Java属性，record构造映射在不同MyBatis版本下更易踩兼容问题。

原因：record的构造映射与MyBatis兼容性不可靠。

解决：聚合查询结果使用Java Bean并显式写`@Results`。

### 5. 旧直连方法内部打开上下文可能覆盖步骤场景

旧的`generateSuggestion(...)`内部调用`LlmUsageContext.open(taskId, "发布前优化")`，若外层已打开workflow step，步骤信息需保留。

原因：直接覆盖会导致步骤归属或场景名丢失。

解决：`LlmUsageContext.open(...)`在已有workflow step时继承workflow字段，并保留外层scene。

---

## 六、前端交互与架构

### 1. 过程弹窗按钮触控区域需满足移动端要求

原按钮基础高度38px不满足触控区域不少于44px的要求。

原因：移动端触控区域过小影响可用性。

解决：主按钮、次级按钮、幽灵按钮和危险按钮的`min-height`统一调整为44px。

### 2. 主页面只保留轻量摘要

原步骤回放在主页面直接展示输入、输出和rawOutput，对UP主阅读建议是噪声。

原因：排查信息不应盖过主要动作。

解决：主页面改成轻量摘要，完整步骤和rawOutput放入过程弹窗且rawOutput默认隐藏。消息流和消息详情统一放入应用内弹窗。

### 3. Vue事件会隐式传入浏览器事件对象

`@click="submitSearch"`会把浏览器事件对象传入函数，若函数第一个参数是page就会把事件对象当成页码。

原因：模板隐式传参会污染请求体。

解决：改为`@click="submitSearch()"`和`@keyup.enter="submitSearch()"`。

### 4. 卡片按钮内不使用段落标签

把整张视频卡片做成button后，内部放`<p>`会导致浏览器自动修正DOM，布局和点击区域异常。

原因：交互元素内部结构需稳定。

解决：检索卡片内部统一用`span`展示摘要、标签、主题命中。

### 5. 上下文不作为用户消息显示

把视频、主题和评论弹幕全部塞进输入框会导致聊天窗口被刷屏。

原因：用户需看清自己的问题和AI回答，材料应作为隐式上下文。

解决：`useAgentChat.sendMessage`支持`displayMessage`和`outboundMessage`分离，前端只显示用户问题，实际请求附带上下文。

### 6. AI浮窗提升为全局常驻组件

原来AI浮窗只在创作工作台页面挂载，案例库页点击卡片时接不到视频上下文事件。

原因：案例库本身也需要AI交互台。

解决：`AgentFloatingWindow`提升为全局常驻组件，默认收起入口不遮挡页面。

---

## 七、数据一致性与事务

### 1. 按BV去重表达不成数据库唯一约束

`bv_id`可空且去重需排除软删（`is_deleted=0`），普通唯一索引无法表达这种带过滤条件且允许多个NULL的约束。

原因：业务幂等条件超出数据库唯一约束表达力。

解决：去重放在导入逻辑中，本批用`Set<String> seenBvIds`去重，配合`countByBvId`查库，无BV的条目不参与去重。

### 2. MyBatis回写null标量参数需显式jdbcType

`updateQualityScores`的评分字段允许为null，MyBatis遇到值为null的标量参数无法推断JDBC类型。

原因：MyBatis靠参数值推断JDBC类型，null时推断不出。

解决：SQL中写成`#{rawQualityScore,jdbcType=DECIMAL}`显式声明类型。

### 3. ONLY_FULL_GROUP_BY下的打分聚合查询

`LEFT JOIN + 条件SUM`查询需`SELECT v.*`但必须`GROUP BY`，MySQL默认开启`ONLY_FULL_GROUP_BY`会报错。

原因：直接SELECT非分组列违反严格模式。

解决：`GROUP BY v.id`（主键），其他`v.*`列对主键函数依赖，MySQL允许直接SELECT；子表无记录时`SUM(CASE...)`自然得0。

### 4. 质量打分必须与导入同一事务

导入后需对受影响分区重算全部视频质量分，若打分另起事务会读不到导入事务里刚插入未提交的新视频。

原因：归一化是分区相对值，新样本会改变该分区min/max，重算与导入逻辑上是一个原子操作。

解决：`recomputeCategories`标`@Transactional`，被已在事务中的导入流程调用时按REQUIRED合并进同一事务。

### 5. 向量索引服务不能加@Transactional

索引服务若包在DB事务里，某批Milvus写入异常触发回滚时，DB状态回滚但Milvus实际写入不受控，状态分叉。

原因：跨外部向量库+本地DB无法用一个大事务统一。

解决：索引服务不标`@Transactional`，按批add+按批回写状态，部分批失败写`failedCount`/`warnings`不整体回滚。

---

## 八、业务逻辑与设计决策

### 1. 单BV采集不能只依赖tname取分区

B站接口`tname`字段不稳定，有时为空，但`tid`存在时可通过映射恢复分区信息。

原因：脚本只取`tname`会把可恢复的分区信息丢掉。

解决：脚本`build_video_item`改为`resolve_video_category`：先取非空tname；tname空但tid存在时用内置`tid->分区名`映射兜底。

### 2. 规则情绪分类要负向优先

关键词分类时，"不实用""不是干货"含正向词（实用/干货）但实为负向，若先判正向会被误标POSITIVE。

原因：规则法是零LLM成本选择，负向优先用最小代价压住最常见误判。

解决：`classifySentiment`先扫负向关键词命中即NEGATIVE，再扫正向，否则NEUTRAL。

### 3. 语境库不等于RAG事实证据

若把语境词条当证据使用，LLM可能把"适合怎么表达"误当成"观众真实反馈"。

原因：评论和弹幕才是判断观众反馈的重点，语境库只提供表达参考。

解决：语境库只进入发布前优化的表达上下文，评论弹幕事实仍由反馈分析和证据检索链路提供。

### 4. 任务类型变化需让任务回到草稿态

创作任务新增`videoType`后，用户可能在已有建议生成后改视频类型，旧建议不再适配。

原因：旧建议基于旧语境生成，继续展示会误导用户。

解决：`CreatorTaskService.updateTask`判断视频类型变化，只要材料或视频类型变化就把任务状态退回DRAFT。

### 5. 拒绝反馈的禁用条件需使用更新后权重

拒绝词条时SQL同时更新`weight`和`enabled`，若`enabled = IF(weight <= 4, 0, enabled)`使用更新前权重，权重刚好从5降到1时不会立即禁用。

原因：禁用判断应基于本次扣分后的权重。

解决：禁用判断改为`enabled = IF(GREATEST(0, weight - 4) <= 4, 0, enabled)`。

### 6. 脚本路径/产物目录不能依赖当前命令行目录

后端`ProcessBuilder`调脚本、脚本写产物若都用相对当前目录的路径，从不同工作目录启动时会找不到脚本。

原因：产物是运行副产物，应稳定落在项目内固定的位置。

解决：后端向上逐级探测包含`scripts/bilibili_reference_fetcher.py`的目录作为项目根；脚本侧用`Path(__file__).resolve().parents[1]`锚定输出目录。

### 7. argparse父子参数需用parent=复用

两个子命令共享同一批通用参数时，若直接加在子解析器上，通用参数写在子命令之后会因argparse父子顺序解析报错。

原因：用`parents=`复用比逐个重复声明更省且顺序友好。

解决：用一个`add_help=False`的父解析器承载通用参数，通过`parents=[common]`挂到各子命令上。

### 8. 创作任务兼容旧体系

若交互式创作完全另起一套数据结构，发布前优化第二阶段拿不到上下文。

解决：创建交互式任务时仍创建标准`creator_task`，用户确认创意卡片后把原始想法和已选创意方向回写到`creator_material.MANUSCRIPT`。

### 9. P0-1的MANUSCRIPT不一定是完整文稿

P0-1把"用户原始想法+已确认创意方向"写入MANUSCRIPT，若P0-2只按材料类型判断"已有文稿"就不会提示用户补充或AI补稿。

解决：前后端使用800字作为第一版保护阈值，短MANUSCRIPT视为创意大纲，允许AI扩写；长MANUSCRIPT或SUBTITLE视为较完整材料不自动覆盖。

### 10. LLM JSON漂移需有兜底

模型可能返回非JSON、字段缺失或options数量不足，若直接抛错会中断流程。

解决：后端先尝试解析JSON，解析失败时记录`parseStatus=RAW_ONLY`，生成兜底卡片保证流程不中断。

### 11. 证据收集不能只依赖Agent自己想起调用工具

若只在Prompt里要求Agent调用`knowledge_search`，模型可能因上下文、温度或输出格式压力而跳过工具。

解决：新增`PrePublishEvidenceCollector`，由代码层前置生成检索query并调用案例检索服务。RAG不可用时转成`SYSTEM_LIMITATION`证据，提示模型保守生成。

### 12. 审查器不直接阻断建议保存

审查规则第一版可能误判，若直接抛错用户可能拿不到任何建议。

解决：本阶段只保存审查报告并用`quality_status`标记质量状态，主流程不中断。第一版只做确定性规则（证据编号、结构完整性、夸大承诺）。

### 13. 旧库需ALTER TABLE兼容补丁

`CREATE TABLE IF NOT EXISTS`不会给已有表增加新字段。

解决：在`init.sql`末尾增加基于`INFORMATION_SCHEMA.COLUMNS`的条件`ALTER TABLE`补齐新字段。

### 14. Redis ping方法不能凭空假设

当前Spring Data Redis版本的顶层`RedisConnection`字节码中无直接声明`ping()`。

原因：引入的方法必须真实可用。

解决：改用已核准的`connection.serverCommands().info()`，能验证Redis连接且不写入数据。

### 15. 运行期开关不直接改Environment

若直接改Spring Environment，启动期Bean不会重新装配，造成"前端显示开了但实际能力未创建"的错觉。

原因：启动期装配与运行期修改不同步。

解决：结构化Agent内核改为调用前读取`RuntimeSettingService`。启动期装配类配置（knowledge.rag.enabled、hybrid.enabled、memory.store-type）只读展示。

---

## 九、其他

### 1. 类级注释会随功能生长而过期

分阶段叠加开发导致部分类级javadoc仍写着旧阶段描述，与实际功能漂移。

原因：分阶段开发难免留下阶段性措辞。

解决：以接口说明文档（`/docs/reference/`）为功能现状准绳，类级注释在下次自然修改时同步校正，不专门起一轮改动。

### 2. 前端新增tab后滑块宽度需同步调整

原工作台tabs数量变化后选中滑块宽比例需同步。

解决：滑块宽度根据实际tab数量等分，移动端关闭滑块改用按钮自身选中态。

### 3. 响应字段扩展需四层同时对齐

`CreatorSuggestionResponse`是Java record，参数数量必须和`toResponse`完全一致；Mapper也要同步select、insert、result map；前端类型也要同步。

原因：record、Mapper、前端类型形成链式依赖。

解决：新增字段时同时更新Record、Response、Mapper和前端类型四层。

### 4. 索引面板迁移不能保留残留onMounted调用

若只删模板不删脚本，会造成无用请求和编译残留。

原因：完整迁移需同时清除import、状态、方法和模板。

解决：完整迁移包括删除索引API import、索引状态、索引方法、onMounted中的索引加载和索引模板区块。

### 5. Git safe.directory在沙箱用户下拦截

Windows仓库owner是Administrator，沙箱用户执行git操作会被拒绝。

原因：Git安全机制检测到仓库所有者与执行用户不一致。

解决：使用单次命令参数`git -c safe.directory=...`，不写全局Git配置避免影响作者本机环境。
