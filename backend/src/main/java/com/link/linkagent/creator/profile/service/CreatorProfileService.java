package com.link.linkagent.creator.profile.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.link.linkagent.creator.preference.mapper.CreatorPreferenceMapper;
import com.link.linkagent.creator.preference.model.CreatorPreferenceRecord;
import com.link.linkagent.creator.profile.mapper.CreatorEventMapper;
import com.link.linkagent.creator.profile.mapper.CreatorProfileMapper;
import com.link.linkagent.creator.profile.model.CreatorEventRecord;
import com.link.linkagent.creator.profile.model.CreatorProfileRecord;
import com.link.linkagent.llm.LLMService;
import com.link.linkagent.prompt.service.PromptService;
import com.link.linkagent.util.TextUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 创作者画像服务。
 * <p>
 * 核心职责：管理创作者的"人格化画像"（风格标签 / 语气偏好 / 受众认知），
 * 通过事件驱动的飞轮闭环实现画像的自动化初始化与增量演进。
 * <p>
 * 架构位置：位于创作者服务层，上游消费来自各分析服务写入的 {@link CreatorEventRecord}，
 * 下游为提示词构建提供画像上下文，供 Agent 在分析时感知创作者风格。
 * <p>
 * 设计决策：
 * <ul>
 *   <li><b>事件统一管理</b>——事件写入与画像更新放在同一个服务内，保证"事件→画像"闭环不跨 Bean，
 *       避免分布式事务问题，也让触发逻辑（事件数达阈值 → 自动更新）直观可见。</li>
 *   <li><b>阈值驱动更新</b>——不每次事件都更新画像，而是累积到 10 条新事件再触发 LLM 汇总，
 *       在"画像时效性"和"LLM 调用成本"之间取平衡。</li>
 *   <li><b>首次自动初始化</b>——用户第一次进入系统时，若无画像则从历史偏好中 LLM 汇总生成初始画像，
 *       省去人工手动创建的步骤，降低创作者上手门槛。</li>
 *   <li><b>更新失败不阻塞</b>——事件记录失败仅记日志不抛异常（避免反馈链路因事件表异常而中断），
 *       画像更新失败跳过本次、下次事件累积后再重试（保证主分析流程不受画像模块影响）。</li>
 * </ul>
 *
 * @see CreatorEventRecord 事件记录模型
 * @see CreatorProfileRecord 画像记录模型
 */
@Service
public class CreatorProfileService {

    private static final Logger log = LoggerFactory.getLogger(CreatorProfileService.class);

    /**
     * 匿名用户 / 未携带 userId 时的默认用户标识。
     * "default" 作为兜底用户，保证画像服务在未登录场景下仍可正常运转。
     */
    private static final String DEFAULT_USER_ID = "default";

    /**
     * 画像增量更新的触发阈值：自上次更新以来累积的新事件数达到此值时，自动调用 LLM 刷新画像。
     * 选择 10 而非更小的数字，是因为：
     * 1) 每次 LLM 画像更新消耗约 2-5k token，10 条事件批处理比逐条更经济；
     * 2) 创作者的风格变化是渐进的，10 条事件足以反映一个可感知的趋势变化。
     */
    private static final int EVENT_TRIGGER_THRESHOLD = 10;

    /**
     * 增量更新时取最近事件的上限条数。
     * 50 条是一个经验值：覆盖了阈值（10）的 5 倍，保证"到达阈值时一定有足够的最近事件可看"，
     * 同时又不会让 LLM 上下文因事件列表过长而膨胀。
     */
    private static final int RECENT_EVENTS_LIMIT = 50;

    /**
     * 初始画像生成时取最近偏好的条数。
     * 5 条足够让 LLM 从用户的修改历史中感知风格倾向，避免取太多导致 prompt 过长。
     */
    private static final int PREFERENCE_HISTORY_LIMIT = 5;

    /** 事件表读写，用于写入创作事件和查询最近事件 */
    private final CreatorEventMapper eventMapper;

    /** 画像表读写，用于查询、插入、更新创作者画像 */
    private final CreatorProfileMapper profileMapper;

    /** 偏好表只读，用于初始化画像时获取历史偏好作为 LLM 输入 */
    private final CreatorPreferenceMapper preferenceMapper;

    /** LLM 调用入口，用于画像生成和增量更新的 AI 推理 */
    private final LLMService llmService;

    /** 提示词模板管理，用于渲染画像相关 prompt 并支持模板 A/B 测试 */
    private final PromptService promptService;

    /** JSON 序列化/反序列化，用于 payloadMap → JSON 转换和 LLM 输出解析 */
    private final ObjectMapper objectMapper;

    /**
     * 构造注入所有依赖。
     * 全部字段用 final 修饰 + 构造注入（而非 @Autowired 字段注入），
     * 保证不可变性和单测中可显式传入 mock 依赖。
     *
     * @param eventMapper     事件表 Mapper
     * @param profileMapper   画像表 Mapper
     * @param preferenceMapper 偏好表 Mapper
     * @param llmService      LLM 调用服务
     * @param promptService   提示词模板服务
     * @param objectMapper    Jackson ObjectMapper
     */
    public CreatorProfileService(CreatorEventMapper eventMapper,
                                 CreatorProfileMapper profileMapper,
                                 CreatorPreferenceMapper preferenceMapper,
                                 LLMService llmService,
                                 PromptService promptService,
                                 ObjectMapper objectMapper) {
        this.eventMapper = eventMapper;
        this.profileMapper = profileMapper;
        this.preferenceMapper = preferenceMapper;
        this.llmService = llmService;
        this.promptService = promptService;
        this.objectMapper = objectMapper;
    }

    /**
     * 获取用户画像，不存在时返回 null。
     * <p>
     * 与 {@link #ensureProfile} 的区别：本方法不做初始化，用于只需要读画像但不强制创建的场景。
     * 典型调用场景：渲染分析页的画像展示区——如果创作者还没进过系统，画像区显示空状态即可，无需触发 LLM 初始化。
     *
     * @param userId 创作者标识；为空时自动归一化为 {@value #DEFAULT_USER_ID}
     * @return 画像记录；不存在时返回 null
     */
    public CreatorProfileRecord getProfile(String userId) {
        return profileMapper.findByCreatorId(normalizeUserId(userId));
    }

    /**
     * 确保用户画像存在——不存在时从历史偏好中由 LLM 汇总生成初始画像。
     * <p>
     * 这是"首次使用时自动初始化"的入口，避免需要人工手动创建画像。
     * 为什么用懒初始化而非服务启动时全量预生成：创作者数量可能很大，全量预生成浪费 LLM token；
     * 只有实际使用系统的创作者才需要画像，按需初始化更经济。
     *
     * @param userId 创作者标识；为空时自动归一化为 {@value #DEFAULT_USER_ID}
     * @return 已有或新创建的画像记录
     */
    public CreatorProfileRecord ensureProfile(String userId) {
        String normalizedUserId = normalizeUserId(userId);
        CreatorProfileRecord existing = profileMapper.findByCreatorId(normalizedUserId);
        if (existing != null) {
            return existing;
        }
        return createInitialProfile(normalizedUserId);
    }

    /**
     * 记录一条创作者事件。
     * <p>
     * 事件写入后自动检查是否达到画像更新阈值，达到则触发增量更新。
     * 为什么事件记录失败不抛异常：事件表是画像飞轮的数据源，但不是主分析链路的必要条件——
     * 因为事件表异常而阻塞用户的分析请求是不可接受的体验降级，所以仅记 warn 日志后正常返回。
     * <p>
     * payloadMap 序列化为 JSON 字符串存入 event 的 payload 字段。
     * 选择 JSON 字符串而非结构化列（如 MySQL JSON 类型）是为了保持与事件表设计的通用性，
     * 后续可无缝迁移到不同数据库。
     *
     * @param userId     创作者标识
     * @param eventType  事件类型（如 PRE_PUBLISH_ANALYSIS / TITLE_GENERATION 等）
     * @param taskId     关联的创作任务 ID，用于事件回溯
     * @param payloadMap 事件携带的键值对数据；为 null 或空时 payload 字段存 null
     */
    public void recordEvent(String userId, String eventType, String taskId, Map<String, Object> payloadMap) {
        try {
            CreatorEventRecord event = new CreatorEventRecord();
            event.setEventId(UUID.randomUUID().toString());
            event.setCreatorId(normalizeUserId(userId));
            event.setEventType(eventType);
            event.setTaskId(taskId);
            // 空 payload 存 null 而非 "{}"：null 在数据库中占空间更小，
            // 且查询时 is null 比 = '{}' 语义更清晰
            event.setPayload(payloadMap != null && !payloadMap.isEmpty()
                    ? objectMapper.writeValueAsString(payloadMap)
                    : null);
            eventMapper.insert(event);
        } catch (Exception e) {
            log.warn("记录创作者事件失败：userId={}, eventType={}, taskId={}", userId, eventType, taskId, e);
        }
    }

    /**
     * 检查新事件数是否达到阈值，达到则触发画像增量更新。
     * <p>
     * 放在事件记录之后调用，让画像更新在事件流水驱动下自然发生。
     * 为什么更新失败不抛异常：本次跳过，下次事件累积到阈值后再重试，
     * 保证单次 LLM 调用异常不会让画像永久停滞。
     * <p>
     * 算法：以画像记录的上次更新时间（update_time）为起点，统计该时间之后的新事件数。
     * 为什么用 update_time 而非独立计数器：update_time 是数据库原生的行级字段，
     * 不需要额外维护一个"自上次更新以来事件数"的状态列，避免了并发更新时的竞态问题。
     *
     * @param userId 创作者标识
     */
    public void tryTriggerProfileUpdate(String userId) {
        String normalizedUserId = normalizeUserId(userId);
        CreatorProfileRecord profile = profileMapper.findByCreatorId(normalizedUserId);
        if (profile == null) {
            return;
        }
        // 以画像上次更新时间为起点，统计这之后的新事件数
        // 为什么用 update_time 而非独立计数器字段：update_time 与画像更新事务原子化，
        // 不需要担心"计数重置了但画像没更新"的不一致问题
        LocalDateTime sinceTime = profile.getUpdateTime();
        int newEventCount = eventMapper.countNewEvents(normalizedUserId, sinceTime);
        if (newEventCount < EVENT_TRIGGER_THRESHOLD) {
            return;
        }
        try {
            updateProfileFromEvents(normalizedUserId, profile);
        } catch (Exception e) {
            log.warn("画像增量更新失败，本次跳过：userId={}", normalizedUserId, e);
        }
    }

    /**
     * 构建注入到系统提示词中的画像上下文。
     * <p>
     * 返回格式为人类可读的中文摘要（"【创作者画像】\n风格标签：...\n语气偏好：...\n受众认知：..."），
     * 设计为可直接拼接进 Agent 的 system prompt 末尾，让 LLM 在分析时感知创作者风格。
     * <p>
     * 为什么返回空字符串而非 null：下游直接做字符串拼接，空字符串天然不产生额外字符，
     * 比 null 更安全（避免拼接时出现 "null" 字符串）。
     * 为什么三个字段都为空时返回空字符串：此时画像是刚创建的空占位，
     * 没有可用的画像信息，注入空内容不会对 LLM 分析产生干扰。
     *
     * @param userId 创作者标识
     * @return 画像上下文文本；画像不存在或为空时返回空字符串
     */
    public String buildProfilePromptContext(String userId) {
        CreatorProfileRecord profile = getProfile(userId);
        if (profile == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        builder.append("\n\n【创作者画像】\n");

        String styleTags = profile.getStyleTags();
        if (TextUtil.hasText(styleTags)) {
            builder.append("风格标签：").append(styleTags).append("\n");
        }
        String toneGuide = profile.getToneGuide();
        if (TextUtil.hasText(toneGuide)) {
            builder.append("语气偏好：").append(toneGuide).append("\n");
        }
        String audienceView = profile.getAudienceView();
        if (TextUtil.hasText(audienceView)) {
            builder.append("受众认知：").append(audienceView).append("\n");
        }

        // 如果画像三个字段都为空，返回空（首次初始化尚未完成的情况）
        // 此时画像是刚用 ensureProfile 创建的空占位，还没有 LLM 填充内容
        String result = builder.toString().trim();
        if ("【创作者画像】".equals(result)) {
            return "";
        }
        return result;
    }

    /**
     * 手动触发画像刷新。
     * <p>
     * 与 {@link #tryTriggerProfileUpdate} 的区别：本方法不检查阈值，直接执行 LLM 更新。
     * 用于管理后台的"刷新画像"按钮或定时任务——运营人员可以随时强制更新创作者的画像，
     * 而不需要等待事件累积到阈值。
     * <p>
     * 为什么先 ensureProfile 再 updateProfileFromEvents：
     * 如果该用户还没有画像（新注册且无任何事件），ensureProfile 会从偏好中创建初始画像；
     * 如果已有画像，ensureProfile 直接返回已有记录，updateProfileFromEvents 在此基础上增量更新。
     *
     * @param userId 创作者标识
     * @return 刷新后的最新画像记录
     */
    public CreatorProfileRecord refreshProfile(String userId) {
        String normalizedUserId = normalizeUserId(userId);
        CreatorProfileRecord profile = ensureProfile(normalizedUserId);
        updateProfileFromEvents(normalizedUserId, profile);
        // 重新从数据库查询最新画像，而非直接返回内存中的 profile 对象。
        // 原因：updateProfileFromEvents 内部虽然调了 profileMapper.update，
        // 但 update_time、MySQL 触发器等字段可能和内存对象不同步，查库保证返回的是数据库真实状态。
        return profileMapper.findByCreatorId(normalizedUserId);
    }

    /**
     * 从历史偏好中由 LLM 汇总生成初始画像。
     * <p>
     * 算法流程：
     * <ol>
     *   <li>查询用户最近的 {@value #PREFERENCE_HISTORY_LIMIT} 条偏好记录</li>
     *   <li>如果无任何偏好 → 创建一个仅有 creator_id 的空画像占位（styleTags="[]"，其余空串）</li>
     *   <li>有偏好 → 将偏好拼接成 summary 文本，调用 LLM 汇总生成 styleTags / toneGuide / audienceView</li>
     *   <li>LLM 调用失败时静默降级为创建空画像占位，保证主流程不中断</li>
     * </ol>
     * 为什么创建空画像占位而非返回 null：空占位让后续事件可以驱动增量更新；
     * 如果返回 null，每次 tryTriggerProfileUpdate 都会因为 "profile == null" 而跳过，
     * 导致该用户永远无法进入飞轮闭环。
     *
     * @param userId 归一化后的创作者标识
     * @return 新创建的画像记录（已持久化到数据库）
     */
    private CreatorProfileRecord createInitialProfile(String userId) {
        List<CreatorPreferenceRecord> preferences = preferenceMapper.listByUserId(userId, PREFERENCE_HISTORY_LIMIT);
        CreatorProfileRecord profile = new CreatorProfileRecord();
        profile.setCreatorId(userId);

        if (preferences.isEmpty()) {
            // 无历史偏好：创建空画像占位，后续有事件后再增量更新填充。
            // 空 styleTags 用 "[]" 而非 null：JSON 反序列化时 "[]" 表示空数组，
            // null 会导致下游解析时报 NPE
            profile.setStyleTags("[]");
            profile.setToneGuide("");
            profile.setAudienceView("");
            profileMapper.insert(profile);
            return profileMapper.findByCreatorId(userId);
        }

        // 有历史偏好：调用 LLM 汇总生成初始画像。
        // 为什么从偏好而非从事件生成初始画像：初始画像发生在"用户首次进入系统"时，
        // 此时可能还没有任何事件记录；但偏好可能已经在之前的分析交互中被收集（如"标题换一个风格"），
        // 偏好是比事件更早可用的信号源。
        String preferenceSummary = preferences.stream()
                .map(p -> "[" + p.getSourceTaskId() + "] " + p.getPreferenceContent())
                .collect(Collectors.joining("\n"));
        String userPrompt = promptService.render("creator_profile.init.user", Map.of(
                "preferenceSummary", preferenceSummary
        ));
        try {
            String rawOutput = llmService.chat(
                    promptService.get("creator_profile.init.system"),
                    userPrompt
            );
            // LLM 返回 JSON 格式：{ "styleTags": [...], "toneGuide": "...", "audienceView": "..." }
            // parseProfileOutput 内部有容错解析逻辑，解析失败时保留字段旧值
            parseProfileOutput(profile, rawOutput);
        } catch (Exception e) {
            log.warn("LLM 初始画像生成失败，创建空画像占位：userId={}", userId, e);
            // LLM 失败时静默降级为空占位：用户仍可正常使用系统，画像在后续事件驱动下逐步填充。
            // 为什么不重试：LLM 失败通常是临时的模型/网络问题，重试可能加重负载；
            // 下次事件累积到阈值时自然会触发增量更新，届时画像可被填充。
            profile.setStyleTags("[]");
            profile.setToneGuide("");
            profile.setAudienceView("");
        }
        profileMapper.insert(profile);
        return profileMapper.findByCreatorId(userId);
    }

    /**
     * 从最近事件中由 LLM 增量更新画像。
     * <p>
     * 算法流程：
     * <ol>
     *   <li>查询用户最近的 {@value #RECENT_EVENTS_LIMIT} 条事件</li>
     *   <li>将事件摘要 + 当前画像拼接为 user prompt，让 LLM 判断是否需要调整画像</li>
     *   <li>LLM 返回更新后的 JSON，解析并写回数据库</li>
     * </ol>
     * 为什么只传当前画像 + 最近事件，而非全量事件：
     * 1) Token 成本——全量事件列表可能很长，但 LLM 只需要最近的信号来判断趋势变化；
     * 2) 画像本身就是历史信息的压缩——当前画像已经蕴含了历史事件累积的信息，
     *    增量更新只需要看"上次更新之后的增量变化"。
     * <p>
     * 为什么最近事件为空时直接返回：没有新事件意味着没有新的行为信号，
     * 此时刷新画像只会产生完全相同的输出，浪费一次 LLM 调用。
     *
     * @param userId         归一化后的创作者标识
     * @param currentProfile 当前画像记录（内存对象，会被原地修改后写回数据库）
     */
    private void updateProfileFromEvents(String userId, CreatorProfileRecord currentProfile) {
        List<CreatorEventRecord> recentEvents = eventMapper.listRecentByCreator(userId, RECENT_EVENTS_LIMIT);
        if (recentEvents.isEmpty()) {
            return;
        }
        // 将事件列表格式化为 "[事件类型] payload (taskId=xxx)" 的文本摘要。
        // 选择这种紧凑格式而非完整 JSON：减少 LLM prompt 的 token 消耗，
        // 让 LLM 聚焦于事件类型和内容本身的语义，而非元数据结构。
        String eventsSummary = recentEvents.stream()
                .map(e -> "[" + e.getEventType() + "] "
                        + (TextUtil.hasText(e.getPayload()) ? e.getPayload() : "无详情")
                        + " (taskId=" + e.getTaskId() + ")")
                .collect(Collectors.joining("\n"));

        String currentProfileText = buildCurrentProfileText(currentProfile);
        String userPrompt = promptService.render("creator_profile.update.user", Map.of(
                "currentProfile", currentProfileText,
                "recentEvents", eventsSummary
        ));
        try {
            String rawOutput = llmService.chat(
                    promptService.get("creator_profile.update.system"),
                    userPrompt
            );
            parseProfileOutput(currentProfile, rawOutput);
            profileMapper.update(currentProfile);
        } catch (Exception e) {
            log.warn("LLM 画像增量更新失败：userId={}", userId, e);
            // 异常不抛——画像更新失败不应阻塞主流程。
            // 即使本次更新失败，下次事件累积到阈值后仍会触发重试。
        }
    }

    /**
     * 解析 LLM 返回的 JSON 输出，填充画像的三个字段。
     * <p>
     * LLM 期望输出格式：
     * <pre>{@code
     * {
     *   "styleTags": ["热血", "搞笑", "科普"],
     *   "toneGuide": "轻松幽默，适合年轻人",
     *   "audienceView": "16-25岁学生群体，偏好二次元内容"
     * }
     * }</pre>
     * <p>
     * 容错策略：
     * <ul>
     *   <li>兼容 LLM 输出包裹在 Markdown 代码块（\`\`\`json ... \`\`\`）中的情况——先 strip 掉代码块标记再解析</li>
     *   <li>三个字段分别尝试解析，缺失的字段不做赋值——保留画像对象上的旧值</li>
     *   <li>整体解析失败时记 warn 日志并保留旧值——不因一次 LLM 输出格式异常就清掉整个画像</li>
     * </ul>
     * 为什么对三个字段分别 try/不整体回滚：LLM 输出可能部分字段有效、部分字段格式异常。
     * 例如 styleTags 解析成功但 audienceView 的 JSON 错误——此时应保留能用的部分，
     * 而不是因为一个字段的问题丢弃全部三个字段的更新。
     *
     * @param profile   画像记录（会被原地修改，填充解析出的字段值）
     * @param rawOutput LLM 的原始输出文本
     */
    private void parseProfileOutput(CreatorProfileRecord profile, String rawOutput) {
        try {
            String jsonText = rawOutput;
            // 兼容 LLM 输出可能包裹在 markdown 代码块中的情况。
            // 为什么这里用 replaceAll 而非 StringUtils.strip：LLM 可能在 json 前后附加
            // 任意文本（如 "好的，这是创作者画像：```json\n..."），strip 只能去掉首尾空白，
            // 需要用 replaceAll 移除 ```json 和 ``` 标记。
            if (jsonText.contains("```")) {
                jsonText = jsonText.replaceAll("```json\\s*", "")
                        .replaceAll("```\\s*", "")
                        .trim();
            }
            com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(jsonText);
            // 对三个字段分别判存在性，缺字段不赋值——保留旧值
            if (root.has("styleTags")) {
                profile.setStyleTags(objectMapper.writeValueAsString(root.get("styleTags")));
            }
            if (root.has("toneGuide") && !root.get("toneGuide").isNull()) {
                profile.setToneGuide(root.get("toneGuide").asText());
            }
            if (root.has("audienceView") && !root.get("audienceView").isNull()) {
                profile.setAudienceView(root.get("audienceView").asText());
            }
        } catch (Exception e) {
            // JSON 解析失败仅记日志，不抛异常——保留画像旧值
            log.warn("画像 JSON 解析失败，保留旧值：{}", e.getMessage());
        }
    }

    /**
     * 将画像记录格式化为人类可读的文本摘要。
     * 用于拼接到 LLM 增量更新的 user prompt 中，让 LLM 看到"当前画像是什么"后判断是否需要调整。
     *
     * @param profile 画像记录
     * @return 格式化的中文文本，如 "风格标签：["热血"]\n语气偏好：轻松幽默\n受众认知：16-25岁"
     */
    private String buildCurrentProfileText(CreatorProfileRecord profile) {
        return "风格标签：" + TextUtil.trimToDefault(profile.getStyleTags(), "暂无")
                + "\n语气偏好：" + TextUtil.trimToDefault(profile.getToneGuide(), "暂无")
                + "\n受众认知：" + TextUtil.trimToDefault(profile.getAudienceView(), "暂无");
    }

    /**
     * 归一化 userId 输入：空/null/空白字符串统一映射为 {@value #DEFAULT_USER_ID}。
     * <p>
     * 为什么在服务层归一化而非 Controller 层：Controller 层可能来自多个入口（REST API / WebSocket / 定时任务），
     * 放在服务层保证所有调用路径一致处理，避免遗漏。这符合"防御性编程"的原则——服务层不信任调用方传来的值。
     *
     * @param userId 原始用户标识，可能为空
     * @return 归一化后的非空用户标识
     */
    private String normalizeUserId(String userId) {
        return TextUtil.trimToDefault(userId, DEFAULT_USER_ID);
    }
}
