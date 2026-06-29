package com.link.linkagent.creator.interactive.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.link.linkagent.creator.interactive.mapper.CreatorInteractiveMapper;
import com.link.linkagent.creator.interactive.model.CreativeIdeaOptionRecord;
import com.link.linkagent.creator.interactive.model.CreativeIdeaOptionResponse;
import com.link.linkagent.creator.interactive.model.CreativeOptionsRegenerateRequest;
import com.link.linkagent.creator.interactive.model.InteractiveSessionRecord;
import com.link.linkagent.creator.interactive.model.InteractiveTaskCreateRequest;
import com.link.linkagent.creator.interactive.model.InteractiveTaskResponse;
import com.link.linkagent.creator.task.model.CreatorTaskCreateRequest;
import com.link.linkagent.creator.task.model.CreatorTaskUpdateRequest;
import com.link.linkagent.creator.task.model.CreatorTaskResponse;
import com.link.linkagent.creator.task.service.CreatorTaskService;
import com.link.linkagent.llm.LLMService;
import com.link.linkagent.llm.usage.LlmUsageContext;
import com.link.linkagent.util.LlmJsonUtil;
import com.link.linkagent.util.TextUtil;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * AI 交互式创作服务。
 * 当前只落地第一阶段最小闭环：输入想法、生成三张创意卡片、确认其中一张。
 */
@Service
public class CreatorInteractiveService {

    private static final String DEFAULT_USER_ID = "default";
    private static final String DEFAULT_VIDEO_TYPE = "未分类";
    private static final int TASK_NAME_MAX_LENGTH = 48;
    private static final int OPTION_NAME_MAX_LENGTH = 128;
    private static final int TASK_MATERIAL_MAX_LENGTH = 20000;
    private static final int OPTION_COUNT = 3;

    private final CreatorInteractiveMapper creatorInteractiveMapper;
    private final CreatorTaskService creatorTaskService;
    private final LLMService llmService;
    private final ObjectMapper objectMapper;

    public CreatorInteractiveService(CreatorInteractiveMapper creatorInteractiveMapper,
                                     CreatorTaskService creatorTaskService,
                                     LLMService llmService,
                                     ObjectMapper objectMapper) {
        this.creatorInteractiveMapper = creatorInteractiveMapper;
        this.creatorTaskService = creatorTaskService;
        this.llmService = llmService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public InteractiveTaskResponse createInteractiveTask(InteractiveTaskCreateRequest request) {
        String userId = normalizeUserId(request.userId());
        String idea = normalizeIdea(request.idea());
        String videoType = normalizeVideoType(request.videoType());

        CreatorTaskResponse task = creatorTaskService.createTask(new CreatorTaskCreateRequest(
                userId,
                buildTaskName(idea),
                videoType,
                null,
                null,
                buildIdeaMaterial(idea),
                null
        ));

        InteractiveSessionRecord session = new InteractiveSessionRecord();
        session.setSessionId(UUID.randomUUID().toString());
        session.setTaskId(task.taskId());
        session.setUserId(userId);
        session.setIdea(idea);
        session.setVideoType(videoType);
        session.setStatus("CREATIVE_GENERATING");
        session.setParseStatus("PENDING");
        creatorInteractiveMapper.insertSession(session);

        generateAndStoreOptions(session, null);
        return getInteractiveTask(task.taskId());
    }

    @Transactional
    public InteractiveTaskResponse regenerateOptions(String taskId, CreativeOptionsRegenerateRequest request) {
        InteractiveSessionRecord session = getSessionRecord(taskId);
        String extraRequirement = request == null ? null : TextUtil.trimToNull(request.extraRequirement());
        creatorInteractiveMapper.clearSelectedOptions(session.getSessionId());
        creatorInteractiveMapper.updateSessionSelection(session.getSessionId(), "CREATIVE_GENERATING", null);
        generateAndStoreOptions(session, extraRequirement);
        return getInteractiveTask(session.getTaskId());
    }

    @Transactional
    public InteractiveTaskResponse confirmOption(String taskId, String optionId) {
        String safeTaskId = normalizeTaskId(taskId);
        String safeOptionId = normalizeOptionId(optionId);
        InteractiveSessionRecord session = getSessionRecord(safeTaskId);
        CreativeIdeaOptionRecord selectedOption = creatorInteractiveMapper
                .findOptionByTaskIdAndOptionId(session.getTaskId(), safeOptionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "创意卡片不存在"));

        creatorInteractiveMapper.clearSelectedOptions(session.getSessionId());
        int selected = creatorInteractiveMapper.selectOption(session.getSessionId(), selectedOption.getOptionId());
        if (selected == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "创意卡片不存在");
        }
        creatorInteractiveMapper.updateSessionSelection(
                session.getSessionId(),
                "CREATIVE_CONFIRMED",
                selectedOption.getOptionId()
        );

        updateTaskMaterialsBySelectedOption(session, selectedOption);
        return getInteractiveTask(session.getTaskId());
    }

    public InteractiveTaskResponse getInteractiveTask(String taskId) {
        InteractiveSessionRecord session = getSessionRecord(taskId);
        List<CreativeIdeaOptionResponse> options = creatorInteractiveMapper
                .listOptionsBySessionId(session.getSessionId())
                .stream()
                .map(this::toOptionResponse)
                .toList();
        return toTaskResponse(session, options);
    }

    private void generateAndStoreOptions(InteractiveSessionRecord session, String extraRequirement) {
        String rawOutput = "";
        List<CreativeIdeaOptionRecord> options;
        String parseStatus = "PARSED";
        try (LlmUsageContext.UsageScope ignored = LlmUsageContext.open(session.getTaskId(), "AI创意方案")) {
            rawOutput = llmService.chat(buildCreativeSystemPrompt(), buildCreativeUserPrompt(session, extraRequirement));
            options = parseOptions(session, rawOutput);
        } catch (RuntimeException exception) {
            parseStatus = "RAW_ONLY";
            rawOutput = TextUtil.hasText(rawOutput) ? rawOutput : "AI 创意生成失败：" + exception.getMessage();
            options = buildFallbackOptions(session);
        }

        creatorInteractiveMapper.deleteOptionsBySessionId(session.getSessionId());
        for (CreativeIdeaOptionRecord option : options) {
            creatorInteractiveMapper.insertOption(option);
        }
        creatorInteractiveMapper.updateSessionGenerationResult(
                session.getSessionId(),
                "CREATIVE_OPTIONS_READY",
                rawOutput,
                parseStatus
        );
    }

    private List<CreativeIdeaOptionRecord> parseOptions(InteractiveSessionRecord session, String rawOutput) {
        try {
            JsonNode rootNode = objectMapper.readTree(LlmJsonUtil.extractJsonObject(rawOutput));
            JsonNode optionsNode = rootNode.get("options");
            if (optionsNode == null || !optionsNode.isArray() || optionsNode.size() == 0) {
                throw new IllegalArgumentException("LLM 输出缺少 options 数组");
            }
            List<CreativeIdeaOptionRecord> records = new ArrayList<>();
            for (int index = 0; index < Math.min(OPTION_COUNT, optionsNode.size()); index++) {
                records.add(toOptionRecord(session, optionsNode.get(index), index));
            }
            while (records.size() < OPTION_COUNT) {
                records.add(buildFallbackOption(session, records.size()));
            }
            return records;
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "AI 创意卡片解析失败", exception);
        }
    }

    private CreativeIdeaOptionRecord toOptionRecord(InteractiveSessionRecord session, JsonNode optionNode, int index) {
        CreativeIdeaOptionRecord record = new CreativeIdeaOptionRecord();
        record.setOptionId(UUID.randomUUID().toString());
        record.setSessionId(session.getSessionId());
        record.setTaskId(session.getTaskId());
        record.setOptionName(TextUtil.abbreviate(
                defaultText(LlmJsonUtil.text(optionNode, "optionName"), "创意方向 " + (index + 1)),
                OPTION_NAME_MAX_LENGTH
        ));
        record.setTargetAudience(defaultText(LlmJsonUtil.text(optionNode, "targetAudience"), "对这个主题感兴趣的 B 站观众"));
        record.setTitleOutline(jsonField(optionNode, "titleOutline", "标题突出主题、收益和观看门槛"));
        record.setContentOutline(jsonField(optionNode, "contentOutline", "开头抛出问题，主体分段说明，结尾给出行动建议"));
        record.setDescriptionOutline(jsonField(optionNode, "descriptionOutline", "简介说明视频价值、关键词和互动引导"));
        record.setSellingPoints(jsonField(optionNode, "sellingPoints", "贴合用户原始想法，便于快速进入制作"));
        record.setRiskPoints(jsonField(optionNode, "riskPoints", "需要避免标题过度承诺或内容泛泛而谈"));
        record.setRecommendReason(defaultText(LlmJsonUtil.text(optionNode, "recommendReason"), "这个方向最容易从当前想法落地成视频。"));
        record.setSelected(false);
        return record;
    }

    private String jsonField(JsonNode optionNode, String fieldName, String fallbackText) {
        try {
            String json = LlmJsonUtil.json(objectMapper, optionNode, fieldName);
            if (TextUtil.hasText(json)) {
                return json;
            }
            return objectMapper.writeValueAsString(List.of(fallbackText));
        } catch (JsonProcessingException exception) {
            return "[\"" + fallbackText + "\"]";
        }
    }

    private List<CreativeIdeaOptionRecord> buildFallbackOptions(InteractiveSessionRecord session) {
        List<CreativeIdeaOptionRecord> options = new ArrayList<>();
        for (int index = 0; index < OPTION_COUNT; index++) {
            options.add(buildFallbackOption(session, index));
        }
        return options;
    }

    private CreativeIdeaOptionRecord buildFallbackOption(InteractiveSessionRecord session, int index) {
        String[] names = {"问题切入型", "教程拆解型", "观点复盘型"};
        String[] audiences = {
                "已经对主题有兴趣，但还没有形成清晰理解的观众",
                "想快速学会方法、希望步骤明确的新手观众",
                "关心经验总结、想判断自己能否复用的创作者或学习者"
        };
        CreativeIdeaOptionRecord record = new CreativeIdeaOptionRecord();
        record.setOptionId(UUID.randomUUID().toString());
        record.setSessionId(session.getSessionId());
        record.setTaskId(session.getTaskId());
        record.setOptionName(names[index % names.length]);
        record.setTargetAudience(audiences[index % audiences.length]);
        record.setTitleOutline(toJsonList(
                "用一个具体问题引出主题：" + TextUtil.abbreviate(session.getIdea(), 50),
                "标题里给出明确收益，避免只写概念名"
        ));
        record.setContentOutline(toJsonList(
                "开头说明观众为什么现在需要看",
                "主体按背景、关键步骤、常见误区展开",
                "结尾给出可执行清单或下一步建议"
        ));
        record.setDescriptionOutline(toJsonList(
                "第一句概括本期能解决的问题",
                "补充适合人群和关键词",
                "引导观众评论自己的使用场景"
        ));
        record.setSellingPoints(toJsonList("不需要完整脚本也能先推进选题", "能直接进入发布前优化阶段"));
        record.setRiskPoints(toJsonList("需要后续补充真实素材", "标题不能承诺视频里没有覆盖的结果"));
        record.setRecommendReason("这是 AI 输出异常时的兜底方向，先保证创作流程不中断，后续可重新生成。");
        record.setSelected(false);
        return record;
    }

    private void updateTaskMaterialsBySelectedOption(InteractiveSessionRecord session,
                                                     CreativeIdeaOptionRecord selectedOption) {
        CreatorTaskResponse currentTask = creatorTaskService.getTask(session.getTaskId());
        creatorTaskService.updateTask(session.getTaskId(), new CreatorTaskUpdateRequest(
                currentTask.taskName(),
                session.getVideoType(),
                buildTitleDraft(selectedOption),
                buildDescriptionDraft(selectedOption),
                buildConfirmedIdeaMaterial(session, selectedOption),
                findMaterial(currentTask, "SUBTITLE")
        ));
    }

    private String buildTitleDraft(CreativeIdeaOptionRecord option) {
        return TextUtil.abbreviate(TextUtil.collapseWhitespace(option.getOptionName()), 200);
    }

    private String buildDescriptionDraft(CreativeIdeaOptionRecord option) {
        String content = """
                创意名称：%s
                适合人群：%s
                简介大纲：%s
                """.formatted(
                option.getOptionName(),
                TextUtil.trimToDefault(option.getTargetAudience(), "未提供"),
                TextUtil.trimToDefault(option.getDescriptionOutline(), "未提供")
        );
        return TextUtil.abbreviateWithSuffix(content.trim(), 2000, "\n[内容过长，已截断]");
    }

    private String buildConfirmedIdeaMaterial(InteractiveSessionRecord session, CreativeIdeaOptionRecord option) {
        String content = """
                【用户原始想法】
                %s

                【已确认创意方向】
                %s

                【适合人群】
                %s

                【标题大纲】
                %s

                【内容大纲】
                %s

                【简介大纲】
                %s

                【亮点】
                %s

                【风险】
                %s

                【AI 建议】
                %s
                """.formatted(
                session.getIdea(),
                option.getOptionName(),
                TextUtil.trimToDefault(option.getTargetAudience(), "未提供"),
                TextUtil.trimToDefault(option.getTitleOutline(), "未提供"),
                TextUtil.trimToDefault(option.getContentOutline(), "未提供"),
                TextUtil.trimToDefault(option.getDescriptionOutline(), "未提供"),
                TextUtil.trimToDefault(option.getSellingPoints(), "未提供"),
                TextUtil.trimToDefault(option.getRiskPoints(), "未提供"),
                TextUtil.trimToDefault(option.getRecommendReason(), "未提供")
        );
        return TextUtil.abbreviateWithSuffix(content.trim(), TASK_MATERIAL_MAX_LENGTH, "\n[内容过长，已截断]");
    }

    private String findMaterial(CreatorTaskResponse task, String materialType) {
        return task.materials()
                .stream()
                .filter(material -> materialType.equals(material.materialType()))
                .findFirst()
                .map(material -> TextUtil.trimToNull(material.content()))
                .orElse(null);
    }

    private String buildCreativeSystemPrompt() {
        return """
                你是 B 站内容创作者的选题策划助手。
                你的任务是把用户的一段自然语言创作想法，拆成 3 个可选创意方向。
                你必须只输出 JSON 对象，不要输出 Markdown，不要解释 JSON 之外的内容。
                每个数组字段必须是字符串数组，每张卡片都要具体到当前用户想法，不要写通用套话。
                """;
    }

    private String buildCreativeUserPrompt(InteractiveSessionRecord session, String extraRequirement) {
        return """
                请基于下面信息生成 3 张 B 站视频创意卡片。

                用户原始想法：
                %s

                视频类型：
                %s

                本轮额外要求：
                %s

                输出 JSON 字段固定如下：
                {
                  "options": [
                    {
                      "optionName": "创意名称，一句话概括这个方向",
                      "targetAudience": "适合人群",
                      "titleOutline": ["标题表达方向1", "标题骨架2"],
                      "contentOutline": ["开头怎么切入", "主体怎么展开", "结尾怎么收束"],
                      "descriptionOutline": ["简介第一句", "关键词", "互动引导"],
                      "sellingPoints": ["亮点1", "亮点2"],
                      "riskPoints": ["风险1", "风险2"],
                      "recommendReason": "AI 推荐或不推荐这个方向的具体理由"
                    }
                  ]
                }

                约束：
                1. options 必须恰好 3 个。
                2. 不要给最终标题，只给标题大纲和可选骨架。
                3. 每张卡片的创意角度必须明显不同。
                4. 风险必须具体指出可能跑偏、过度承诺或观众误解的地方。
                5. 所有内容使用中文。
                """.formatted(
                session.getIdea(),
                TextUtil.trimToDefault(session.getVideoType(), DEFAULT_VIDEO_TYPE),
                TextUtil.trimToDefault(extraRequirement, "无")
        );
    }

    private String buildIdeaMaterial(String idea) {
        return "【用户原始创作想法】\n" + TextUtil.abbreviateWithSuffix(idea, TASK_MATERIAL_MAX_LENGTH, "\n[内容过长，已截断]");
    }

    private String buildTaskName(String idea) {
        return TextUtil.abbreviate(TextUtil.collapseWhitespace(idea), TASK_NAME_MAX_LENGTH);
    }

    private InteractiveSessionRecord getSessionRecord(String taskId) {
        return creatorInteractiveMapper.findSessionByTaskId(normalizeTaskId(taskId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "交互式创作任务不存在"));
    }

    private InteractiveTaskResponse toTaskResponse(InteractiveSessionRecord session,
                                                   List<CreativeIdeaOptionResponse> options) {
        return new InteractiveTaskResponse(
                session.getTaskId(),
                session.getSessionId(),
                session.getUserId(),
                session.getIdea(),
                session.getVideoType(),
                session.getStatus(),
                session.getSelectedOptionId(),
                session.getParseStatus(),
                session.getCreateTime(),
                session.getUpdateTime(),
                options
        );
    }

    private CreativeIdeaOptionResponse toOptionResponse(CreativeIdeaOptionRecord record) {
        return new CreativeIdeaOptionResponse(
                record.getId(),
                record.getOptionId(),
                record.getSessionId(),
                record.getTaskId(),
                record.getOptionName(),
                record.getTargetAudience(),
                record.getTitleOutline(),
                record.getContentOutline(),
                record.getDescriptionOutline(),
                record.getSellingPoints(),
                record.getRiskPoints(),
                record.getRecommendReason(),
                Boolean.TRUE.equals(record.getSelected()),
                record.getCreateTime(),
                record.getUpdateTime()
        );
    }

    private String normalizeUserId(String userId) {
        return TextUtil.trimToDefault(userId, DEFAULT_USER_ID);
    }

    private String normalizeIdea(String idea) {
        String normalized = TextUtil.trimToNull(idea);
        if (normalized == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "创作想法不能为空");
        }
        return normalized;
    }

    private String normalizeVideoType(String videoType) {
        return TextUtil.trimToDefault(videoType, DEFAULT_VIDEO_TYPE);
    }

    private String normalizeTaskId(String taskId) {
        String normalized = TextUtil.trimToNull(taskId);
        if (normalized == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "任务ID不能为空");
        }
        return normalized;
    }

    private String normalizeOptionId(String optionId) {
        String normalized = TextUtil.trimToNull(optionId);
        if (normalized == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "创意卡片ID不能为空");
        }
        return normalized;
    }

    private String defaultText(String value, String defaultValue) {
        return TextUtil.trimToDefault(value, defaultValue);
    }

    private String toJsonList(String... values) {
        try {
            return objectMapper.writeValueAsString(List.of(values));
        } catch (JsonProcessingException exception) {
            return "[]";
        }
    }
}
