package com.link.linkagent.creator.evaluation.service;

import com.link.linkagent.creator.evaluation.mapper.CreatorEvaluationMapper;
import com.link.linkagent.creator.evaluation.model.CreatorEvalCaseCreateRequest;
import com.link.linkagent.creator.evaluation.model.CreatorEvalCaseRecord;
import com.link.linkagent.creator.evaluation.model.CreatorEvalCaseResponse;
import com.link.linkagent.creator.evaluation.model.CreatorEvalPromptVersionStatsResponse;
import com.link.linkagent.creator.evaluation.model.CreatorEvalResultCreateRequest;
import com.link.linkagent.creator.evaluation.model.CreatorEvalResultRecord;
import com.link.linkagent.creator.evaluation.model.CreatorEvalResultResponse;
import com.link.linkagent.util.NumberUtil;
import com.link.linkagent.util.TextUtil;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 内容评估服务 —— 面向 B 站创作者的 AI 内容质量评测体系。
 * <p>
 * 核心职责：
 * <ol>
 *   <li><b>评测用例管理</b>：定义"测什么"——创建评测用例（case），绑定到任务的特定阶段
 *       （PRE_PUBLISH / FEEDBACK / REPORT），并记录输入快照、预期要点、评分标准。</li>
 *   <li><b>评测结果记录</b>：记录"测得怎么样"——每次 AI 模型运行后的输出、Token 用量、
 *       七个维度的评分（可读性/相关性/完整性/准确性/稳定性/成本/可解释性）、解析状态等。</li>
 *   <li><b>提示词版本对比</b>：按 promptVersion 分组聚合所有评测结果，计算各版本的成功率、
 *       各维度平均分、Token 消耗统计，帮助创作者判断哪个提示词版本的 AI 输出质量更高。</li>
 * </ol>
 * <p>
 * 架构定位：本服务是纯"数据存取 + 基础校验"层，不包含 AI 调用逻辑。
 * AI 评估由上层编排器完成（如 CreatorWorkflowOrchestrator），本服务只负责把评估结果落库和查询。
 * 这样分离保证了评测数据的"写入"和"评估逻辑"解耦——改变评估方式（如替换评分模型）不需要改数据库结构。
 * <p>
 * 设计权衡：
 * <ul>
 *   <li>七个维度的评分字段（readability / relevance / completeness / accuracy / stability / cost /
 *       explainability）全部设计为可选的整数——因为不同阶段的评测可能只关注部分维度，强制全维度打分
 *       会增加录入成本且产生无意义的默认值。</li>
 *   <li>parseStatus 字段（PARSED / RAW_ONLY）用于区分 AI 输出是否被成功解析为结构化 JSON。
 *       保留 RAW_ONLY 状态的数据不丢弃，因为后续可能改进解析器后重新解析历史数据。</li>
 *   <li>promptHash 基于 SHA-256 自动计算，用于在不暴露完整提示词内容的前提下判断两次评测是否
 *       使用了同一份提示词。请求方也可以显式传入自定义 hash，方便对接外部实验平台。</li>
 * </ul>
 */
@Service
public class CreatorEvaluationService {

    // === 业务常量 ===

    /** 匿名用户的默认标识，用于未登录场景下的评测数据归属 */
    private static final String DEFAULT_USER_ID = "default";
    /** 列表查询的默认分页大小：20 条平衡了前端展示体验和数据库查询开销 */
    private static final int DEFAULT_LIMIT = 20;
    /** 列表查询的最大分页上限：100 条防止单次查询拉取过多数据导致内存压力 */
    private static final int MAX_LIMIT = 100;
    /** 评测用例的活跃状态 —— 当前只有 ACTIVE，后续可能扩展 ARCHIVED 支持软删除 */
    private static final String STATUS_ACTIVE = "ACTIVE";
    /** 评测运行状态：成功 */
    private static final String RUN_STATUS_SUCCESS = "SUCCESS";
    /** 评测运行状态：失败（通过 failureReason 字段记录具体原因） */
    private static final String RUN_STATUS_FAILED = "FAILED";
    /** AI 输出已被成功解析为结构化 JSON（至少以 { 开头 } 结尾） */
    private static final String PARSE_STATUS_PARSED = "PARSED";
    /** AI 输出未被结构化解板解析，仅保留原始文本 */
    private static final String PARSE_STATUS_RAW_ONLY = "RAW_ONLY";

    /** 评测用例与结果的持久化映射器（MyBatis Mapper），只做 SQL 操作不包含业务逻辑 */
    private final CreatorEvaluationMapper creatorEvaluationMapper;

    public CreatorEvaluationService(CreatorEvaluationMapper creatorEvaluationMapper) {
        this.creatorEvaluationMapper = creatorEvaluationMapper;
    }

    /**
     * 创建评测用例。
     * <p>
     * 评测用例定义了"测什么"：绑定到某个创作任务的特定阶段（PRE_PUBLISH / FEEDBACK / REPORT），
     * 并记录当时的输入快照（inputSnapshot）、预期输出要点（expectedPoints）和评分标准（scoringRubric）。
     * 后续对同一个 caseId 可以反复提交评测结果（recordResult），形成同一用例的多次运行记录，
     * 从而对比不同提示词版本或模型配置的质量差异。
     * <p>
     * 为什么用 UUID 作为 caseId 而非自增 ID？caseId 是前端可见的业务标识，UUID 避免自增 ID
     * 被猜测导致信息泄露；同时 UUID 支持离线生成，方便对接外部评测平台。
     * <p>
     * 设计权衡：targetStage 必须为合法的枚举值，非法值直接 400 拒绝——不在 Service 层做模糊匹配
     * 或默认值推断，因为评测阶段是整个评测体系的"坐标系"，坐标系错了后面的数据全都没意义。
     *
     * @param request 评测用例创建请求，至少包含 caseName、targetStage、inputSnapshot
     * @return 创建成功后的完整评测用例响应（含自动生成的 caseId 和 createTime）
     */
    @Transactional
    public CreatorEvalCaseResponse createCase(CreatorEvalCaseCreateRequest request) {
        CreatorEvalCaseRecord record = new CreatorEvalCaseRecord();
        // UUID 作为业务主键：全局唯一 + 不可猜测 + 支持离线生成
        record.setCaseId(UUID.randomUUID().toString());
        // userId 缺省时使用 "default"，保证匿名用户的评测数据不会丢失归属
        record.setUserId(TextUtil.trimToDefault(request.userId(), DEFAULT_USER_ID));
        record.setCaseName(request.caseName().trim());
        // 阶段校验在 normalizeStage 内部完成，非法阶段直接抛 400
        record.setTargetStage(normalizeStage(request.targetStage()));
        // taskId 为可选字段：评测用例可以独立于具体创作任务存在（如通用剧本评测）
        record.setTaskId(TextUtil.trimToNull(request.taskId()));
        record.setInputSnapshot(request.inputSnapshot().trim());
        record.setExpectedPoints(TextUtil.trimToNull(request.expectedPoints()));
        record.setScoringRubric(TextUtil.trimToNull(request.scoringRubric()));
        // 新创建的用例默认为 ACTIVE，后续可通过软删除（改为 ARCHIVED）下线
        record.setStatus(STATUS_ACTIVE);
        creatorEvaluationMapper.insertCase(record);
        // 插入后通过 getCase 回读，保证返回的响应与数据库一致（包括 createTime 等自动填充字段）
        return getCase(record.getCaseId());
    }

    /**
     * 按条件分页查询评测用例列表。
     * <p>
     * 支持按 userId 和 targetStage 过滤，limit 分页。所有参数都是可选的：
     * userId 为空时查所有用户，targetStage 为空时查所有阶段，limit 为空时默认 20。
     * <p>
     * 为什么用 limit 而非 offset/pageSize 分页？评测用例数量在创作者场景下不会很大
     * （一般几十到百级别），offset 分页的复杂度收益不明显，简单的 limit 已足够。
     * 后续如果用例数量增长到千级别，再引入游标分页也不影响现有调用方。
     * <p>
     * 防御性校验：limit 通过 NumberUtil.limitOrDefault 钳位到 [1, MAX_LIMIT]，防止
     * 恶意传 0 或极大值导致 SQL 性能问题或内存溢出。
     *
     * @param userId 可选，按用户过滤评测用例
     * @param targetStage 可选，按阶段过滤（PRE_PUBLISH / FEEDBACK / REPORT）
     * @param limit 可选，返回条数上限，默认 20，最大 100
     * @return 评测用例响应列表（已按 createTime 降序排列）
     */
    public List<CreatorEvalCaseResponse> listCases(String userId, String targetStage, Integer limit) {
        // limit 钳位到 [1, MAX_LIMIT]，防止恶意参数导致性能问题
        int safeLimit = NumberUtil.limitOrDefault(limit, DEFAULT_LIMIT, MAX_LIMIT);
        // userId/targetStage 做 trim + null 转空处理，"" 和 null 语义一致表示"不限"
        String safeUserId = TextUtil.trimToNull(userId);
        String safeStage = normalizeOptionalStage(targetStage);
        return creatorEvaluationMapper.listCases(safeUserId, safeStage, safeLimit)
                .stream()
                .map(this::toCaseResponse)
                .toList();
    }

    /**
     * 按 caseId 查询单个评测用例。
     * <p>
     * 不存在时抛出 404，让前端明确区分"空列表"与"资源不存在"两种状态。
     *
     * @param caseId 评测用例的业务标识
     * @return 完整的评测用例响应
     */
    public CreatorEvalCaseResponse getCase(String caseId) {
        CreatorEvalCaseRecord record = getCaseRecord(caseId);
        return toCaseResponse(record);
    }

    /**
     * 记录一次评测运行结果。
     * <p>
     * 这是评测体系的核心写入方法。每次 AI 运行后调用此方法记录输出质量和元数据，
     * 同一 caseId 可多次调用（每次运行一条新记录），形成同一评测用例的多次运行历史。
     * <p>
     * 核心校验逻辑（按优先级排序）：
     * <ol>
     *   <li><b>用例存在性</b>：caseId 必须对应一个已存在的评测用例，否则无法关联。</li>
     *   <li><b>阶段一致性</b>：request 的 targetStage 必须与用例的 targetStage 一致。
     *       一个用例的生命周期内评测阶段固定，不允许同一 caseId 跨阶段评测，
     *       因为 PRE_PUBLISH 和 FEEDBACK 阶段的评分标准完全不同。</li>
     *   <li><b>结果必要字段</b>：rawOutput 和 failureReason 至少提供一个。
     *       允许只记录失败原因（如 API 超时），也允许只记录输出（正常情况）。</li>
     *   <li><b>Token 用量标准化</b>：null 或 <=0 的 Token 值统一转 null，
     *       避免把"未采集到用量"当成"零成本"影响统计。</li>
     *   <li><b>解析状态自动判定</b>：rawOutput 以 { 开头 } 结尾视为 PARSED，
     *       否则标记 RAW_ONLY（可能是纯文本输出或 JSON 被额外的 markdown 包裹）。</li>
     * </ol>
     * <p>
     * 设计权衡：为什么 promptHash 支持两种来源（请求显式传入 vs 自动计算 SHA-256）？
     * 外部实验平台可能已有自己的 prompt 版本管理机制，显式传入 hash 可以沿用外部标识；
     * 对于没有外部标识的场景，自动计算 SHA-256 保证数据自包含。
     *
     * @param caseId 关联的评测用例标识
     * @param request 评测结果创建请求，至少包含 rawOutput 或 failureReason
     * @return 创建成功后的完整评测结果响应
     */
    @Transactional
    public CreatorEvalResultResponse recordResult(String caseId, CreatorEvalResultCreateRequest request) {
        CreatorEvalCaseRecord caseRecord = getCaseRecord(caseId);
        String targetStage = normalizeStage(request.targetStage());
        if (!caseRecord.getTargetStage().equals(targetStage)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "评测结果阶段必须与评测用例阶段保持一致");
        }

        String rawOutput = TextUtil.trimToNull(request.rawOutput());
        String failureReason = TextUtil.trimToNull(request.failureReason());
        if (rawOutput == null && failureReason == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "评测结果必须提供模型输出或失败原因");
        }

        CreatorEvalResultRecord record = new CreatorEvalResultRecord();
        record.setResultId(UUID.randomUUID().toString());
        record.setCaseId(caseRecord.getCaseId());
        record.setTaskId(resolveTaskId(caseRecord.getTaskId(), request.taskId()));
        record.setWorkflowSessionId(TextUtil.trimToNull(request.workflowSessionId()));
        record.setTargetStage(targetStage);
        record.setModelName(TextUtil.trimToNull(request.modelName()));
        record.setPromptVersion(TextUtil.trimToNull(request.promptVersion()));
        record.setPromptSnapshot(TextUtil.trimToNull(request.promptSnapshot()));
        record.setPromptHash(resolvePromptHash(request.promptHash(), record.getPromptSnapshot()));
        record.setOutputSummary(TextUtil.trimToNull(request.outputSummary()));
        record.setRawOutput(rawOutput == null ? failureReason : rawOutput);
        record.setRunStatus(failureReason == null ? RUN_STATUS_SUCCESS : RUN_STATUS_FAILED);
        record.setParseStatus(resolveParseStatus(rawOutput));
        Integer promptTokens = normalizeTokenCount(request.promptTokens());
        Integer completionTokens = normalizeTokenCount(request.completionTokens());
        Integer totalTokens = normalizeTokenCount(request.totalTokens());
        record.setElapsedMs(request.elapsedMs());
        record.setPromptTokens(promptTokens);
        record.setCompletionTokens(completionTokens);
        record.setTotalTokens(resolveTotalTokens(totalTokens, promptTokens, completionTokens));
        record.setFailureReason(failureReason);
        record.setReadabilityScore(request.readabilityScore());
        record.setRelevanceScore(request.relevanceScore());
        record.setCompletenessScore(request.completenessScore());
        record.setAccuracyScore(request.accuracyScore());
        record.setStabilityScore(request.stabilityScore());
        record.setCostScore(request.costScore());
        record.setExplainabilityScore(request.explainabilityScore());
        record.setReviewerNote(TextUtil.trimToNull(request.reviewerNote()));
        creatorEvaluationMapper.insertResult(record);
        return getResult(record.getResultId());
    }

    /**
     * 按 caseId 查询某评测用例的所有运行结果列表。
     * <p>
     * 先做用例存在性校验（getCaseRecord），不存在直接 404，避免返回空列表让前端困惑。
     * 结果按创建时间降序排列（最新的在前），方便查看最近的评估趋势。
     *
     * @param caseId 评测用例标识
     * @param limit 可选，返回条数上限，默认 20
     * @return 评测结果响应列表
     */
    public List<CreatorEvalResultResponse> listResults(String caseId, Integer limit) {
        // 先校验用例存在性，不存在直接 404 而非返回空列表
        getCaseRecord(caseId);
        int safeLimit = NumberUtil.limitOrDefault(limit, DEFAULT_LIMIT, MAX_LIMIT);
        return creatorEvaluationMapper.listResultsByCaseId(caseId.trim(), safeLimit)
                .stream()
                .map(this::toResultResponse)
                .toList();
    }

    /**
     * 按 resultId 查询单条评测结果。
     * <p>
     * 与 getCase 相同的 404 语义：不存在则明确报资源不存在。
     *
     * @param resultId 评测结果的业务标识
     * @return 完整的评测结果响应
     */
    public CreatorEvalResultResponse getResult(String resultId) {
        CreatorEvalResultRecord record = creatorEvaluationMapper.findResultByResultId(normalizeId(resultId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "评测结果不存在"));
        return toResultResponse(record);
    }

    /**
     * 按提示词版本聚合对比某评测用例的所有运行结果。
     * <p>
     * 这是评测体系的分析入口：将同一 caseId 下的所有结果按 promptVersion 分组，
     * 每组计算成功率、七个维度平均分、Token 消耗统计、耗时统计等指标，
     * 帮助创作者直观对比不同提示词版本的 AI 输出质量差异。
     * <p>
     * 聚合逻辑细节：
     * <ul>
     *   <li>未设置 promptVersion 的结果归入 "UNVERSIONED" 分组，不会因缺少版本号而被丢弃。</li>
     *   <li>各维度平均分只统计有值的结果（null 不计入分母），
     *       因为不是每次评测都包含全部七个维度的评分。</li>
     *   <li>标准差计算需要至少有 2 个样本（单样本的标准差为 0，表示结果稳定无波动）。</li>
     *   <li>Token 平均值同样只统计有值的结果，避免"未采集"被当成 0 拉低平均值。</li>
     * </ul>
     * <p>
     * 为什么用 LinkedHashMap 保持插入顺序？按结果的时间顺序分组，保证前端展示的版本
     * 顺序与评测时间一致（最早的版本在前），符合"版本演进"的叙事逻辑。
     *
     * @param caseId 评测用例标识
     * @return 按 promptVersion 分组的统计对比列表（有序）
     */
    public List<CreatorEvalPromptVersionStatsResponse> comparePromptVersions(String caseId) {
        // 先校验用例存在性
        getCaseRecord(caseId);
        // 取全部结果（不做 limit），因为对比分析需要完整数据集才有统计意义
        List<CreatorEvalResultRecord> records = creatorEvaluationMapper.listAllResultsByCaseIdForStats(caseId.trim());
        // LinkedHashMap 保持插入顺序：按评测时间顺序分组，前端展示版本演进叙事
        Map<String, List<CreatorEvalResultRecord>> groupedRecords = new LinkedHashMap<>();
        for (CreatorEvalResultRecord record : records) {
            // promptVersion 为空时归入 "UNVERSIONED"，不丢弃数据
            String promptVersion = TextUtil.trimToDefault(record.getPromptVersion(), "UNVERSIONED");
            groupedRecords.computeIfAbsent(promptVersion, key -> new ArrayList<>()).add(record);
        }
        return groupedRecords.entrySet()
                .stream()
                .map(entry -> toPromptVersionStats(caseId.trim(), entry.getKey(), entry.getValue()))
                .toList();
    }

    /**
     * 根据 caseId 获取评测用例记录，不存在时抛出 404。
     * <p>
     * 抽取为私有方法是避免 listResults、comparePromptVersions 等调用方各自重复
     * 存在性校验和异常构造逻辑。
     *
     * @param caseId 评测用例标识（会自动 trim + 空值校验）
     * @return 数据库中的评测用例记录
     */
    private CreatorEvalCaseRecord getCaseRecord(String caseId) {
        return creatorEvaluationMapper.findCaseByCaseId(normalizeId(caseId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "评测用例不存在"));
    }

    /**
     * 校验并规范化评测阶段（必填字段版本）。
     * <p>
     * 只接受 PRE_PUBLISH、FEEDBACK、REPORT 三个值，其他值直接 400。
     * 不做模糊匹配（如大小写不敏感、前缀匹配），因为评测阶段是整个体系的"坐标系"，
     * 坐标系必须精确，否则数据之间的对比就没有意义。
     *
     * @param stage 原始阶段字符串
     * @return trim 后的规范化阶段字符串
     * @throws ResponseStatusException 如果阶段值不在合法枚举中
     */
    private String normalizeStage(String stage) {
        String normalized = TextUtil.trimToNull(stage);
        if (!isValidStage(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "评测阶段只能是 PRE_PUBLISH、FEEDBACK 或 REPORT");
        }
        return normalized;
    }

    /**
     * 校验并规范化评测阶段（可选字段版本）。
     * <p>
     * 与 normalizeStage 的区别：允许返回 null（表示不限阶段，如 listCases 查询时）。
     * 但如果传入了非空值，仍需通过合法性校验。
     *
     * @param stage 原始阶段字符串，可以为 null 或空
     * @return trim 后的规范化阶段字符串，或 null
     * @throws ResponseStatusException 如果非空但不合法
     */
    private String normalizeOptionalStage(String stage) {
        String normalized = TextUtil.trimToNull(stage);
        if (normalized == null) {
            return null;
        }
        if (!isValidStage(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "评测阶段只能是 PRE_PUBLISH、FEEDBACK 或 REPORT");
        }
        return normalized;
    }

    /**
     * 判断阶段值是否在合法枚举中。
     * <p>
     * 三个阶段的业务含义：
     * <ul>
     *   <li>PRE_PUBLISH：发布前评测，检查内容质量（标题、封面、内容结构）</li>
     *   <li>FEEDBACK：发布后反馈期评测，分析观众反馈和互动数据</li>
     *   <li>REPORT：最终复盘报告，检查发布后视频表现、观众反馈与行动建议</li>
     * </ul>
     *
     * @param stage 待校验的阶段字符串
     * @return true 表示合法
     */
    private boolean isValidStage(String stage) {
        return "PRE_PUBLISH".equals(stage) || "FEEDBACK".equals(stage) || "REPORT".equals(stage);
    }

    /**
     * 解析评测结果关联的任务 ID。
     * <p>
     * 优先级：请求中显式传入的 taskId > 用例创建时绑定的 taskId。
     * 这允许同一条评测用例被复用到不同任务中——例如模板化的"标题评测"用例，
     * 每次复用时请求方传入当前任务的 taskId 即可正确关联。
     *
     * @param caseTaskId 评测用例创建时绑定的任务 ID（可能为 null）
     * @param requestTaskId 本次请求传入的任务 ID（可能为 null）
     * @return 最终的任务 ID，可能为 null
     */
    private String resolveTaskId(String caseTaskId, String requestTaskId) {
        // 请求显式传入优先：支持用例跨任务复用
        if (TextUtil.hasText(requestTaskId)) {
            return requestTaskId.trim();
        }
        return TextUtil.trimToNull(caseTaskId);
    }

    /**
     * 解析 Token 总量。
     * <p>
     * 优先级：显式传入的 totalTokens > 由 promptTokens + completionTokens 推导。
     * 只有输入和输出 Token 都明确时才推导总量，
     * 避免把缺失的一侧当成 0 影响成本统计（例如某模型 API 只返回了 completionTokens）。
     *
     * @param totalTokens 请求方显式传入的总量（可能为 null）
     * @param promptTokens 输入 Token 数
     * @param completionTokens 输出 Token 数
     * @return Token 总量，或 null
     */
    private Integer resolveTotalTokens(Integer totalTokens, Integer promptTokens, Integer completionTokens) {
        // 显式总量优先
        if (totalTokens != null) {
            return totalTokens;
        }
        // 任一侧缺失则不推导，避免把 null 当 0 加法
        if (promptTokens == null || completionTokens == null) {
            return null;
        }
        return promptTokens + completionTokens;
    }

    /**
     * Token 用量标准化。
     * <p>
     * Token 为 null 或 <=0 时统一转 null。背后的判断是：正常的 API 请求至少消耗 1 Token，
     * <=0 的值更可能代表"未采集到 usage 信息"或"返回异常"，
     * 统一转为 null 避免统计层把未知用量当成零成本（会导致成本分析失真）。
     *
     * @param tokenCount 原始 Token 数
     * @return 标准化后的 Token 数，或 null
     */
    private Integer normalizeTokenCount(Integer tokenCount) {
        return tokenCount == null || tokenCount <= 0 ? null : tokenCount;
    }

    /**
     * 自动解析 AI 输出的结构化状态。
     * <p>
     * 判断逻辑：以 { 开头且以 } 结尾 → PARSED（视为合法 JSON）；
     * 否则 → RAW_ONLY（可能是纯文本输出，或 JSON 被 markdown 代码块包裹）。
     * <p>
     * 为什么只做首尾判断而不是完整 JSON 解析？完整解析代价高（大文本的 parse 可能抛异常），
     * 且首尾判断对绝大多数情况已足够——AI 输出的 JSON 要么完整正确，
     * 要么完全不是 JSON（如纯文本错误描述）。真正的 JSON 格式错误（如缺少逗号）
     * 是少数情况，可在后续解析失败时回溯修正。
     * <p>
     * 设计权衡：RAW_ONLY 的数据不丢弃、也不强行解析——保留原始输出，
     * 后续可以改进解析器后重新解析历史数据而不需要重新运行昂贵的 AI 评估。
     *
     * @param rawOutput AI 的原始输出文本
     * @return PARSED 或 RAW_ONLY
     */
    private String resolveParseStatus(String rawOutput) {
        if (TextUtil.isBlank(rawOutput)) {
            return PARSE_STATUS_RAW_ONLY;
        }
        String normalized = rawOutput.trim();
        // 简单首尾判断：{ ... } 视为合法 JSON，省去完整 parse 的开销
        if (normalized.startsWith("{") && normalized.endsWith("}")) {
            return PARSE_STATUS_PARSED;
        }
        return PARSE_STATUS_RAW_ONLY;
    }

    /**
     * 将同一 promptVersion 的一组结果聚合为统计数据。
     * <p>
     * 聚合维度和算法说明：
     * <ul>
     *   <li><b>成功率</b>：SUCCESS 记录数 / 总记录数，反映该版本提示词的稳定性。</li>
     *   <li><b>综合平均分</b>：每条记录取其七个维度评分的均值（有值的维度才算），
     *       再对所有记录的综合分取平均。null 值不计入分母。</li>
     *   <li><b>综合分标准差</b>：衡量同一版本下不同运行的质量波动——标准差越大
     *       说明该版本提示词的输出质量越不稳定，可能需要排查模型温度或提示词歧义问题。</li>
     *   <li><b>各维度平均分</b>：单独统计每个维度的平均分，方便定位"哪个维度是短板"。</li>
     *   <li><b>满分记录数</b>：七个维度全部有评分的记录数，反映评估的完整性。</li>
     *   <li><b>Token 统计</b>：总消耗量（只统计有值的记录）和平均值。</li>
     *   <li><b>最新提示词哈希</b>：取该版本下最近更新的记录的 promptHash，
     *       用于判断是否发生了提示词变更。</li>
     * </ul>
     */
    private CreatorEvalPromptVersionStatsResponse toPromptVersionStats(String caseId,
                                                                       String promptVersion,
                                                                       List<CreatorEvalResultRecord> records) {
        int successCount = 0;
        // 取最新记录的时间与 hash，反映该版本的最新状态
        String latestPromptHash = null;
        LocalDateTime latestUpdateTime = null;
        // 综合评分统计：所有记录七个维度的均值再取平均 + 标准差
        int scoreSampleCount = 0;
        int fullScoreCount = 0;
        double scoreTotal = 0;
        List<Double> scoreSamples = new ArrayList<>();
        // 各维度分项统计：每个维度独立计数，因为不是所有记录都有全部维度
        double readabilityTotal = 0;
        int readabilityCount = 0;
        double relevanceTotal = 0;
        int relevanceCount = 0;
        double completenessTotal = 0;
        int completenessCount = 0;
        double accuracyTotal = 0;
        int accuracyCount = 0;
        double stabilityTotal = 0;
        int stabilityCount = 0;
        double costTotal = 0;
        int costCount = 0;
        double explainabilityTotal = 0;
        int explainabilityCount = 0;
        // Token 消耗统计：long 类型避免大数值溢出，null 不计入计数
        long promptTokenTotal = 0;
        int promptTokenCount = 0;
        long completionTokenTotal = 0;
        int completionTokenCount = 0;
        long totalTokenTotal = 0;
        int totalTokenCount = 0;
        // 耗时统计
        double elapsedTotal = 0;
        int elapsedCount = 0;
        // 单次遍历完成所有聚合，O(n) 时间复杂度
        for (CreatorEvalResultRecord record : records) {
            if (RUN_STATUS_SUCCESS.equals(record.getRunStatus())) {
                successCount++;
            }
            // 追踪最新记录的时间和 hash（后续统计中 latestUpdateTime 反映该版本最后更新时间）
            if (latestUpdateTime == null || isAfter(record.getUpdateTime(), latestUpdateTime)) {
                latestUpdateTime = record.getUpdateTime();
                latestPromptHash = record.getPromptHash();
            }
            // 综合评分：每条记录的七个维度均值
            Double recordScore = recordAverageScore(record);
            if (recordScore != null) {
                scoreSamples.add(recordScore);
                scoreTotal += recordScore;
                scoreSampleCount++;
            }
            // 满分记录：七个维度全部有值
            if (hasAllScoreDimensions(record)) {
                fullScoreCount++;
            }
            // Token 聚合：null 不计入计数（没有 token 信息 vs 消耗 0 token 是两个概念）
            promptTokenTotal = addInteger(promptTokenTotal, record.getPromptTokens());
            promptTokenCount += record.getPromptTokens() == null ? 0 : 1;
            completionTokenTotal = addInteger(completionTokenTotal, record.getCompletionTokens());
            completionTokenCount += record.getCompletionTokens() == null ? 0 : 1;
            totalTokenTotal = addInteger(totalTokenTotal, record.getTotalTokens());
            totalTokenCount += record.getTotalTokens() == null ? 0 : 1;
            elapsedTotal = addLong(elapsedTotal, record.getElapsedMs());
            elapsedCount += record.getElapsedMs() == null ? 0 : 1;
            // 七个维度分项聚合
            readabilityTotal = addInteger(readabilityTotal, record.getReadabilityScore());
            readabilityCount += record.getReadabilityScore() == null ? 0 : 1;
            relevanceTotal = addInteger(relevanceTotal, record.getRelevanceScore());
            relevanceCount += record.getRelevanceScore() == null ? 0 : 1;
            completenessTotal = addInteger(completenessTotal, record.getCompletenessScore());
            completenessCount += record.getCompletenessScore() == null ? 0 : 1;
            accuracyTotal = addInteger(accuracyTotal, record.getAccuracyScore());
            accuracyCount += record.getAccuracyScore() == null ? 0 : 1;
            stabilityTotal = addInteger(stabilityTotal, record.getStabilityScore());
            stabilityCount += record.getStabilityScore() == null ? 0 : 1;
            costTotal = addInteger(costTotal, record.getCostScore());
            costCount += record.getCostScore() == null ? 0 : 1;
            explainabilityTotal = addInteger(explainabilityTotal, record.getExplainabilityScore());
            explainabilityCount += record.getExplainabilityScore() == null ? 0 : 1;
        }
        return new CreatorEvalPromptVersionStatsResponse(
                caseId,
                promptVersion,
                latestPromptHash,
                records.size(),
                successCount,
                percent(successCount, records.size()),
                scoreSampleCount,
                scoreSampleCount == 0 ? null : roundOneDecimal(scoreTotal / scoreSampleCount),
                scoreStandardDeviation(scoreSamples),
                averageValue(readabilityTotal, readabilityCount),
                averageValue(relevanceTotal, relevanceCount),
                averageValue(completenessTotal, completenessCount),
                averageValue(accuracyTotal, accuracyCount),
                averageValue(stabilityTotal, stabilityCount),
                averageValue(costTotal, costCount),
                averageValue(explainabilityTotal, explainabilityCount),
                promptTokenCount == 0 ? null : promptTokenTotal,
                completionTokenCount == 0 ? null : completionTokenTotal,
                totalTokenCount == 0 ? null : totalTokenTotal,
                averageValue(promptTokenTotal, promptTokenCount),
                averageValue(completionTokenTotal, completionTokenCount),
                averageValue(totalTokenTotal, totalTokenCount),
                averageValue(elapsedTotal, elapsedCount),
                percent(fullScoreCount, records.size()),
                latestUpdateTime
        );
    }

    /**
     * 判断 candidate 时间是否在 current 之后。
     * <p>
     * null 安全：candidate 为 null 返回 false（不能比较），current 为 null 返回 true
     * （不存在的时间视为"无限古老"，任何有效时间都在它之后）。
     * 用于 toPromptVersionStats 中追踪该版本的最新更新时间。
     */
    private boolean isAfter(LocalDateTime candidate, LocalDateTime current) {
        return candidate != null && (current == null || candidate.isAfter(current));
    }

    /**
     * 计算一条评测记录中各维度的平均分（只统计有值的维度）。
     * <p>
     * 空值不计入分母：假设一条记录只给可读性打了 8 分，其他维度都为 null，
     * 那么综合分就是 8，而不是被 6 个 null 稀释成 8/7=1.14。
     * 因为 null 表示"未评估"而非"0 分"，两者语义完全相反。
     *
     * @param record 单条评测结果记录
     * @return 有值维度的平均分；没有任何维度有值则返回 null
     */
    private Double recordAverageScore(CreatorEvalResultRecord record) {
        Integer[] scores = {
                record.getReadabilityScore(),
                record.getRelevanceScore(),
                record.getCompletenessScore(),
                record.getAccuracyScore(),
                record.getStabilityScore(),
                record.getCostScore(),
                record.getExplainabilityScore()
        };
        int scoreSum = 0;
        int scoreCount = 0;
        for (Integer score : scores) {
            if (score != null) {
                scoreSum += score;
                scoreCount++;
            }
        }
        // 全部为 null 时返回 null（不是 0），语义上"无法评分"与"0 分"完全不同
        return scoreCount == 0 ? null : (double) scoreSum / scoreCount;
    }

    /**
     * 判断一条评测记录是否涵盖全部七个评分维度。
     * <p>
     * 用于统计"满分记录数"——反映评估的完整性。高比例的满分记录说明该版本
     * 的评测流程覆盖全面，低比例说明某些维度经常被遗漏，可能需要检查评测配置。
     */
    private boolean hasAllScoreDimensions(CreatorEvalResultRecord record) {
        return record.getReadabilityScore() != null
                && record.getRelevanceScore() != null
                && record.getCompletenessScore() != null
                && record.getAccuracyScore() != null
                && record.getStabilityScore() != null
                && record.getCostScore() != null
                && record.getExplainabilityScore() != null;
    }

    /**
     * 安全加法（double + Integer），null 值跳过不计。
     *
     * @param total 累加器当前值
     * @param value 要累加的值，null 时不变
     * @return 新的累加器值
     */
    private double addInteger(double total, Integer value) {
        return value == null ? total : total + value;
    }

    /**
     * 安全加法（long + Integer），null 值跳过不计。
     */
    private long addInteger(long total, Integer value) {
        return value == null ? total : total + value;
    }

    /**
     * 安全加法（double + Long），null 值跳过不计。
     */
    private double addLong(double total, Long value) {
        return value == null ? total : total + value;
    }

    /**
     * 计算平均值并保留一位小数。
     * <p>
     * count 为 0 时返回 null（不是 0），与 Java 标准库的 Double.NaN 行为不同，
     * 原因是 NaN 在 JSON 序列化中表现不确定，null 更安全。
     *
     * @param total 总和
     * @param count 计数
     * @return 平均值（一位小数），无样本时返回 null
     */
    private Double averageValue(double total, int count) {
        return count == 0 ? null : roundOneDecimal(total / count);
    }

    /**
     * 计算百分比并保留一位小数。
     * <p>
     * 分母 <= 0 时返回 null——因为此时计算结果无意义（不是 0%），
     * 前端可以据此显示 "N/A" 而非 "0%" 误导用户。
     *
     * @param numerator 分子
     * @param denominator 分母
     * @return 百分比值（一位小数），无效时返回 null
     */
    private Double percent(int numerator, int denominator) {
        if (denominator <= 0) {
            return null;
        }
        return roundOneDecimal((double) numerator * 100.0 / denominator);
    }

    /**
     * 计算综合评分的总体标准差（总体方差而非样本方差）。
     * <p>
     * 选择总体标准差而非样本标准差的理由：这里是"某版本下所有运行的全部数据"，
     * 不是从总体中抽样——我们拿到的就是该版本的全部评估结果。
     * 样本标准差（除以 n-1）会系统性地高估方差，不适用于全量数据场景。
     * <p>
     * 空列表返回 null（不是 NaN），原因同 averageValue。
     *
     * @param scores 综合评分列表
     * @return 标准差（一位小数），无有效样本时返回 null
     */
    private Double scoreStandardDeviation(List<Double> scores) {
        if (scores.isEmpty()) {
            return null;
        }
        double average = scores.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double variance = scores.stream()
                .mapToDouble(score -> Math.pow(score - average, 2))
                .average()
                .orElse(0);
        return roundOneDecimal(Math.sqrt(variance));
    }

    /**
     * 保留一位小数（四舍五入）。
     * <p>
     * 使用 Math.round(value * 10.0) / 10.0 而非 BigDecimal，因为这里的精度需求
     * 只是一位小数用于前端展示，不需要 BigDecimal 的任意精度和舍入模式控制。
     * 简化实现减少对象分配开销（toPromptVersionStats 中高频调用）。
     */
    private Double roundOneDecimal(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    /**
     * 解析提示词的哈希值。
     * <p>
     * 优先级：请求方显式传入的 promptHash > 基于 promptSnapshot 自动计算的 SHA-256。
     * <p>
     * 为什么需要自动计算哈希？评测进行一段时间后，prompt 的内容可能很大，
     * 不方便每次都完整传输和展示。有了哈希，后续可以隐藏长 Prompt 内容，
     * 仅通过哈希判断两次评测是否真的用了同一份提示词——相同哈希 = 同一份提示词。
     * <p>
     * SHA-256 选型理由：对于去重和版本差异判断，碰撞概率低到可忽略（2^-256），
     * 且 JDK 内置支持，不需要额外依赖。不需要密码学级别的抗碰撞性（SHA-1 也够用），
     * 但 SHA-256 作为行业标准更不容易在代码审查中被质疑。
     * <p>
     * 为什么用 UTF-8 编码？提示词模板通过 PromptService 加载后为 Java String，
     * 转换为 byte 时必须指定编码，UTF-8 是最通用的选译（与 web 生态一致）。
     *
     * @param requestPromptHash 请求方显式传入的哈希（可能为 null）
     * @param promptSnapshot 提示词快照文本（可能为 null）
     * @return 最终的哈希字符串（小写十六进制），或 null
     */
    private String resolvePromptHash(String requestPromptHash, String promptSnapshot) {
        // 显式传入优先：支持外部实验平台的自定义版本标识
        String safeRequestHash = TextUtil.trimToNull(requestPromptHash);
        if (safeRequestHash != null) {
            return safeRequestHash.toLowerCase();
        }
        // 无快照则无法计算哈希
        if (promptSnapshot == null) {
            return null;
        }
        try {
            // SHA-256：JDK 内置，碰撞概率可忽略，适合文本去重/版本对比
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(promptSnapshot.getBytes(StandardCharsets.UTF_8));
            // 字节转十六进制小写字符串（标准哈希表示法）
            StringBuilder builder = new StringBuilder(hashBytes.length * 2);
            for (byte hashByte : hashBytes) {
                builder.append(String.format("%02x", hashByte));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException exception) {
            // SHA-256 是 JDK 强制要求的算法，此异常在正常 JDK 上永远不会触发
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "当前 JDK 不支持 SHA-256 哈希计算");
        }
    }

    /**
     * ID 规范化：trim + 空值校验。
     * <p>
     * 统一在多处 getCaseRecord、getResult 等入口调用，避免各方法各自做校验。
     * 空 ID 直接 400（而非 404），因为空 ID 是请求格式问题而非资源不存在问题。
     */
    private String normalizeId(String value) {
        if (TextUtil.isBlank(value)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ID不能为空");
        }
        return value.trim();
    }

    /**
     * 将数据库评测用例记录转换为前端响应对象。
     * <p>
     * 字段全量映射，不做筛选——前端根据需求自行选择展示哪些字段。
     */
    private CreatorEvalCaseResponse toCaseResponse(CreatorEvalCaseRecord record) {
        return new CreatorEvalCaseResponse(
                record.getId(),
                record.getCaseId(),
                record.getUserId(),
                record.getCaseName(),
                record.getTargetStage(),
                record.getTaskId(),
                record.getInputSnapshot(),
                record.getExpectedPoints(),
                record.getScoringRubric(),
                record.getStatus(),
                record.getCreateTime(),
                record.getUpdateTime()
        );
    }

    private CreatorEvalResultResponse toResultResponse(CreatorEvalResultRecord record) {
        return new CreatorEvalResultResponse(
                record.getId(),
                record.getResultId(),
                record.getCaseId(),
                record.getTaskId(),
                record.getWorkflowSessionId(),
                record.getTargetStage(),
                record.getModelName(),
                record.getPromptVersion(),
                record.getPromptHash(),
                record.getPromptSnapshot(),
                record.getOutputSummary(),
                record.getRawOutput(),
                record.getRunStatus(),
                record.getParseStatus(),
                record.getElapsedMs(),
                record.getPromptTokens(),
                record.getCompletionTokens(),
                record.getTotalTokens(),
                record.getFailureReason(),
                record.getReadabilityScore(),
                record.getRelevanceScore(),
                record.getCompletenessScore(),
                record.getAccuracyScore(),
                record.getStabilityScore(),
                record.getCostScore(),
                record.getExplainabilityScore(),
                record.getReviewerNote(),
                record.getCreateTime(),
                record.getUpdateTime()
        );
    }
}
