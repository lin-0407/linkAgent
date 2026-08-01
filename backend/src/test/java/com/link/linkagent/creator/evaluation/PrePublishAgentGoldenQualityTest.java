package com.link.linkagent.creator.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.link.linkagent.core.AgentExecutor;
import com.link.linkagent.creator.context.service.CreatorContextService;
import com.link.linkagent.creator.preference.service.CreatorPreferenceService;
import com.link.linkagent.creator.suggestion.mapper.CreatorSuggestionMapper;
import com.link.linkagent.creator.suggestion.model.CreatorSuggestionRecord;
import com.link.linkagent.creator.suggestion.model.CreatorSuggestionResponse;
import com.link.linkagent.creator.suggestion.model.PrePublishAnalyzeRequest;
import com.link.linkagent.creator.suggestion.service.PrePublishSuggestionService;
import com.link.linkagent.creator.task.mapper.CreatorTaskMapper;
import com.link.linkagent.creator.task.model.CreatorMaterialRecord;
import com.link.linkagent.creator.task.model.CreatorMaterialType;
import com.link.linkagent.creator.task.model.CreatorTaskRecord;
import com.link.linkagent.creator.task.model.CreatorTaskStatus;
import com.link.linkagent.creator.task.model.CreatorTaskSummaryRecord;
import com.link.linkagent.llm.LLMService;
import com.link.linkagent.prompt.service.PromptService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@EnabledIfSystemProperty(named = "linkagent.golden.prepublish.enabled", matches = "true")
public class PrePublishAgentGoldenQualityTest {

    private static final int DEFAULT_CASE_LIMIT = 36;
    private static final int DEFAULT_MIN_CASE_OVERALL_SCORE = 4;
    private static final int DEFAULT_MIN_DIMENSION_SCORE = 3;
    private static final Path GOLDEN_DATASET_PATH_FROM_BACKEND = Path.of(
            "..",
            "docs",
            "develop",
            "pre_publish_golden_v1_cases.jsonl"
    );
    private static final Path GOLDEN_DATASET_PATH_FROM_ROOT = Path.of(
            "docs",
            "develop",
            "pre_publish_golden_v1_cases.jsonl"
    );

    @Autowired
    private AgentExecutor agentExecutor;

    @Autowired
    private LLMService llmService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PromptService promptService;

    @Test
    void shouldMeetGoldenQualityBarWithRealPrePublishAgent() throws Exception {
        List<GoldenCase> cases = selectCases(readGoldenCases());
        List<String> failures = new ArrayList<>();

        // 真实质量评测必须至少命中一条样例，避免 caseId 写错后空跑假通过。
        assertThat(cases).isNotEmpty();

        for (GoldenCase goldenCase : cases) {
            try {
                CreatorSuggestionResponse response = runPrePublishAgent(goldenCase);
                List<String> structuralIssues = validateAgentOutputStructure(response);
                QualityJudgeResult judgeResult = judgeAgentOutput(goldenCase, response);

                if (!structuralIssues.isEmpty() || !isQualityPassed(judgeResult)) {
                    failures.add(formatFailure(goldenCase, response, structuralIssues, judgeResult));
                }
            } catch (RuntimeException exception) {
                failures.add(goldenCase.caseId() + " 执行失败：" + exception.getMessage());
            }
        }

        assertThat(failures)
                .as("发布前优化 Agent 必须通过金标准集质量门槛")
                .isEmpty();
    }

    private CreatorSuggestionResponse runPrePublishAgent(GoldenCase goldenCase) {
        FakeCreatorTaskMapper taskMapper = new FakeCreatorTaskMapper(goldenCase);
        FakeCreatorSuggestionMapper suggestionMapper = new FakeCreatorSuggestionMapper();
        // 这里只替换数据库 Mapper，AgentExecutor、LLMService 和 PromptService 都使用真实 Bean，确保评测对象是真实发布前优化 Agent。
        PrePublishSuggestionService service = new PrePublishSuggestionService(
                taskMapper,
                suggestionMapper,
                emptyPreferenceService(),
                contextServiceFor(goldenCase),
                null,
                llmService,
                agentExecutor,
                objectMapper,
                promptService,
                null,
                null
        );

        return service.generateSuggestionByAgent(goldenCase.taskId(), buildAnalyzeRequest(goldenCase));
    }

    private PrePublishAnalyzeRequest buildAnalyzeRequest(GoldenCase goldenCase) {
        JsonNode styleContext = goldenCase.inputSnapshot().path("styleContext");
        JsonNode inputMaterials = goldenCase.inputSnapshot().path("inputMaterials");
        // 金标准里的网感边界要进入发布前优化输入，否则 Agent 无法区分“该抽象”和“该克制”的场景。
        String customGuidance = """
                创作者当前困境：%s
                创作者目标：%s
                目标观众：%s
                创作者人设：%s
                梗和抽象表达容忍度：%s
                抽象表达策略：%s
                需要避开的表达：%s
                """.formatted(
                text(goldenCase.inputSnapshot(), "creatorProblem"),
                text(inputMaterials, "creatorGoal"),
                text(styleContext, "targetAudience"),
                text(styleContext, "creatorPersona"),
                text(styleContext, "memeTolerance"),
                text(styleContext, "abstractStrategy"),
                styleContext.path("avoidStyle").toString()
        );

        return new PrePublishAnalyzeRequest(
                customGuidance,
                "优先服务本期输入材料，不读取历史偏好。",
                titleStyleFor(styleContext),
                "建议必须能直接用于改标题、改开头、改结构、改简介或改标签；不要编造输入材料外的数据。",
                "IGNORE_HISTORY",
                analysisStrategyFor(goldenCase)
        );
    }

    private List<String> validateAgentOutputStructure(CreatorSuggestionResponse response) {
        List<String> issues = new ArrayList<>();

        // 结构完整是质量评测的前置条件，字段缺失会让人工或 LLM 裁判无法判断具体能力。
        if (!"PARSED".equals(response.parseStatus())) {
            issues.add("输出没有被解析为结构化 JSON，parseStatus=" + response.parseStatus());
        }
        if (isBlank(response.creatorDilemma())) {
            issues.add("缺少 creatorDilemma，无法判断是否理解创作者困境");
        }
        if (isBlank(response.audienceHook())) {
            issues.add("缺少 audienceHook，无法判断点击和留存钩子");
        }
        if (isBlank(response.titleSuggestions())) {
            issues.add("缺少 titleSuggestions，无法评测标题优化能力");
        }
        if (isBlank(response.actionableRevisionPlan())) {
            issues.add("缺少 actionableRevisionPlan，无法评测可执行修改计划");
        }
        if (isBlank(response.riskPoints())) {
            issues.add("缺少 riskPoints，无法评测风险边界");
        }

        return issues;
    }

    private QualityJudgeResult judgeAgentOutput(GoldenCase goldenCase,
                                                CreatorSuggestionResponse response) throws IOException {
        String systemPrompt = """
                你是 B 站发布前优化质量评测员。
                你的任务是根据金标准样例评估 Agent 输出质量，而不是重新给创作建议。
                请保持严格：泛泛建议、标题党、硬套热梗、忽略创作者困境、不可直接执行，都要扣分。
                请只输出 JSON 对象，不要输出 Markdown。
                """;
        // LLM-as-judge 只负责按金标准打分，不重新生成建议，避免把评测和创作混在一起。
        String userPrompt = """
                【金标准样例】
                caseId：%s
                caseName：%s

                【输入快照】
                %s

                【期望能力点】
                %s

                【评分规则】
                %s

                【Agent 输出】
                %s

                请按 1 到 5 分评分：
                - expectedCoverageScore：是否覆盖期望能力点。
                - relevanceScore：是否贴合本期材料、目标观众和创作者困境。
                - inspirationQualityScore：是否给出真正能拍、能点、有差异化的灵感。
                - internetSenseScore：是否具备 B 站语境下的网感和梗适配能力；低梗场景也要能克制。
                - actionabilityScore：是否能直接执行到标题、开头、结构、简介或标签。
                - riskControlScore：是否守住不可编造、不过度标题党、不误导的边界。
                - overallScore：综合分。

                JSON 字段固定如下：
                {
                  "expectedCoverageScore": 1,
                  "relevanceScore": 1,
                  "inspirationQualityScore": 1,
                  "internetSenseScore": 1,
                  "actionabilityScore": 1,
                  "riskControlScore": 1,
                  "overallScore": 1,
                  "verdict": "PASS/FAIL",
                  "matchedExpectedPoints": ["已满足的期望点"],
                  "missedExpectedPoints": ["遗漏的期望点"],
                  "criticalIssues": ["主要问题"]
                }
                """.formatted(
                goldenCase.caseId(),
                goldenCase.caseName(),
                objectMapper.writeValueAsString(goldenCase.inputSnapshot()),
                objectMapper.writeValueAsString(goldenCase.expectedPoints()),
                objectMapper.writeValueAsString(goldenCase.scoringRubric()),
                objectMapper.writeValueAsString(toJudgeOutput(response))
        );

        return llmService.chatStructured(systemPrompt, userPrompt, QualityJudgeResult.class);
    }

    private boolean isQualityPassed(QualityJudgeResult judgeResult) {
        int minOverallScore = Integer.getInteger(
                "linkagent.golden.prepublish.minOverallScore",
                DEFAULT_MIN_CASE_OVERALL_SCORE
        );
        int minDimensionScore = Integer.getInteger(
                "linkagent.golden.prepublish.minDimensionScore",
                DEFAULT_MIN_DIMENSION_SCORE
        );

        // 综合分要求更高，单项分要求兜底，避免总分看起来不错但网感或风险边界明显短板。
        return score(judgeResult.overallScore()) >= minOverallScore
                && score(judgeResult.expectedCoverageScore()) >= minDimensionScore
                && score(judgeResult.relevanceScore()) >= minDimensionScore
                && score(judgeResult.inspirationQualityScore()) >= minDimensionScore
                && score(judgeResult.internetSenseScore()) >= minDimensionScore
                && score(judgeResult.actionabilityScore()) >= minDimensionScore
                && score(judgeResult.riskControlScore()) >= minDimensionScore;
    }

    private ObjectNode toJudgeOutput(CreatorSuggestionResponse response) {
        ObjectNode outputNode = objectMapper.createObjectNode();
        outputNode.put("contentSummary", response.contentSummary());
        outputNode.put("creatorDilemma", response.creatorDilemma());
        outputNode.put("audienceProfile", response.audienceProfile());
        outputNode.put("audienceHook", response.audienceHook());
        outputNode.put("contentPositioning", response.contentPositioning());
        outputNode.put("sellingPoints", response.sellingPoints());
        outputNode.put("riskPoints", response.riskPoints());
        outputNode.put("titleSuggestions", response.titleSuggestions());
        outputNode.put("descriptionSuggestion", response.descriptionSuggestion());
        outputNode.put("actionableRevisionPlan", response.actionableRevisionPlan());
        outputNode.put("tagSuggestions", response.tagSuggestions());
        outputNode.put("partitionSuggestion", response.partitionSuggestion());
        outputNode.put("rawOutput", response.rawOutput());
        return outputNode;
    }

    private String formatFailure(GoldenCase goldenCase,
                                 CreatorSuggestionResponse response,
                                 List<String> structuralIssues,
                                 QualityJudgeResult judgeResult) {
        return """
                %s %s 未达标
                结构问题：%s
                分数：expected=%d, relevance=%d, inspiration=%d, internetSense=%d, actionability=%d, risk=%d, overall=%d
                结论：%s
                遗漏期望点：%s
                关键问题：%s
                Agent原始输出：%s
                """.formatted(
                goldenCase.caseId(),
                goldenCase.caseName(),
                structuralIssues,
                score(judgeResult.expectedCoverageScore()),
                score(judgeResult.relevanceScore()),
                score(judgeResult.inspirationQualityScore()),
                score(judgeResult.internetSenseScore()),
                score(judgeResult.actionabilityScore()),
                score(judgeResult.riskControlScore()),
                score(judgeResult.overallScore()),
                judgeResult.verdict(),
                safeList(judgeResult.missedExpectedPoints()),
                safeList(judgeResult.criticalIssues()),
                response.rawOutput()
        );
    }

    private List<GoldenCase> readGoldenCases() throws IOException {
        Path datasetPath = resolveGoldenDatasetPath();
        List<GoldenCase> cases = new ArrayList<>();

        for (String line : Files.readAllLines(datasetPath, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) {
                JsonNode rootNode = objectMapper.readTree(line);
                cases.add(new GoldenCase(
                        text(rootNode, "caseId"),
                        text(rootNode, "caseName"),
                        text(rootNode, "taskId"),
                        rootNode.path("tags"),
                        rootNode.path("inputSnapshot"),
                        rootNode.path("expectedPoints"),
                        rootNode.path("scoringRubric")
                ));
            }
        }

        return cases;
    }

    private List<GoldenCase> selectCases(List<GoldenCase> cases) {
        String caseIds = System.getProperty("linkagent.golden.prepublish.caseIds", "").trim();
        if (!caseIds.isEmpty()) {
            Set<String> selectedIds = Arrays.stream(caseIds.split(","))
                    .map(String::trim)
                    .filter(caseId -> !caseId.isEmpty())
                    .collect(Collectors.toSet());
            return cases.stream()
                    .filter(goldenCase -> selectedIds.contains(goldenCase.caseId()))
                    .toList();
        }

        int caseLimit = Integer.getInteger("linkagent.golden.prepublish.caseLimit", DEFAULT_CASE_LIMIT);
        return cases.stream()
                .limit(Math.max(1, caseLimit))
                .toList();
    }

    private Path resolveGoldenDatasetPath() {
        Path backendRelativePath = GOLDEN_DATASET_PATH_FROM_BACKEND.toAbsolutePath().normalize();
        if (Files.exists(backendRelativePath)) {
            return backendRelativePath;
        }

        Path rootRelativePath = GOLDEN_DATASET_PATH_FROM_ROOT.toAbsolutePath().normalize();
        assertThat(rootRelativePath)
                .as("发布前金标准集 JSONL 文件必须存在")
                .exists();
        return rootRelativePath;
    }

    private CreatorPreferenceService emptyPreferenceService() {
        return new CreatorPreferenceService(null) {
            @Override
            public String buildPromptContext(String userId) {
                return "本轮金标准评测不读取历史创作者偏好，只使用当前样例输入。";
            }
        };
    }

    private CreatorContextService contextServiceFor(GoldenCase goldenCase) {
        return new CreatorContextService(null) {
            @Override
            public String buildPromptContext(String userId, String videoType, String scene) {
                JsonNode styleContext = goldenCase.inputSnapshot().path("styleContext");
                return """
                        当前样例视频类型：%s
                        目标观众：%s
                        创作者人设：%s
                        梗和抽象表达容忍度：%s
                        抽象表达策略：%s
                        避免表达：%s
                        """.formatted(
                        text(goldenCase.inputSnapshot(), "videoType"),
                        text(styleContext, "targetAudience"),
                        text(styleContext, "creatorPersona"),
                        text(styleContext, "memeTolerance"),
                        text(styleContext, "abstractStrategy"),
                        styleContext.path("avoidStyle")
                );
            }
        };
    }

    private String titleStyleFor(JsonNode styleContext) {
        String memeTolerance = text(styleContext, "memeTolerance");
        if ("high".equals(memeTolerance)) {
            return "允许更强网感和抽象表达，但核心信息必须看得懂";
        }
        if ("low".equals(memeTolerance)) {
            return "克制、清楚、可信，不强行玩梗";
        }
        return "有轻度网感，但不能牺牲信息清晰度";
    }

    private String analysisStrategyFor(GoldenCase goldenCase) {
        String caseText = goldenCase.caseName() + goldenCase.tags() + text(goldenCase.inputSnapshot(), "videoType");
        if (caseText.contains("教程")) {
            return "TUTORIAL";
        }
        if (caseText.contains("Vlog")) {
            return "VLOG";
        }
        if (caseText.contains("测评") || caseText.contains("评测") || caseText.contains("开箱")) {
            return "REVIEW";
        }
        if (caseText.contains("评论") || caseText.contains("观点") || caseText.contains("热点") || caseText.contains("吐槽")) {
            return "COMMENTARY";
        }
        return "GENERAL";
    }

    private String text(JsonNode node, String fieldName) {
        return node.path(fieldName).asText();
    }

    private boolean isBlank(String text) {
        return text == null || text.trim().isEmpty();
    }

    private int score(Integer score) {
        return score == null ? 0 : score;
    }

    private List<String> safeList(List<String> values) {
        return values == null ? List.of() : values;
    }

    private record GoldenCase(String caseId,
                              String caseName,
                              String taskId,
                              JsonNode tags,
                              JsonNode inputSnapshot,
                              JsonNode expectedPoints,
                              JsonNode scoringRubric) {
    }

    public record QualityJudgeResult(Integer expectedCoverageScore,
                                     Integer relevanceScore,
                                     Integer inspirationQualityScore,
                                     Integer internetSenseScore,
                                     Integer actionabilityScore,
                                     Integer riskControlScore,
                                     Integer overallScore,
                                     String verdict,
                                     List<String> matchedExpectedPoints,
                                     List<String> missedExpectedPoints,
                                     List<String> criticalIssues) {
    }

    private static class FakeCreatorTaskMapper implements CreatorTaskMapper {

        private final CreatorTaskRecord taskRecord;
        private final List<CreatorMaterialRecord> materials;

        private FakeCreatorTaskMapper(GoldenCase goldenCase) {
            this.taskRecord = createTaskRecord(goldenCase);
            this.materials = createMaterialRecords(goldenCase);
        }

        @Override
        public int insertTask(CreatorTaskRecord record) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int upsertMaterial(CreatorMaterialRecord record) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int deleteMaterialByType(String taskId, String materialType) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<CreatorTaskRecord> findTaskByTaskId(String taskId) {
            return Optional.of(taskRecord);
        }

        @Override
        public List<CreatorMaterialRecord> listMaterialsByTaskId(String taskId) {
            return materials;
        }

        @Override
        public List<CreatorTaskSummaryRecord> listTasksByUser(String userId, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<CreatorTaskSummaryRecord> listRecentTasks(int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int updateTaskStatus(String taskId, String status) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int markPlanningSkipped(String taskId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int updateTaskName(String taskId, String taskName) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int updateTaskBasicInfo(String taskId, String taskName, String videoType) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int deleteTask(String taskId, String status) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int deleteMaterialsByTaskId(String taskId) {
            throw new UnsupportedOperationException();
        }

        private static CreatorTaskRecord createTaskRecord(GoldenCase goldenCase) {
            CreatorTaskRecord record = new CreatorTaskRecord();
            record.setId(1L);
            record.setTaskId(goldenCase.taskId());
            record.setUserId("golden-eval");
            record.setTaskName(textOf(goldenCase.inputSnapshot().path("inputMaterials"), "taskName"));
            record.setVideoType(textOf(goldenCase.inputSnapshot(), "videoType"));
            record.setStatus(CreatorTaskStatus.DRAFT.name());
            record.setCreateTime(LocalDateTime.now());
            record.setUpdateTime(LocalDateTime.now());
            return record;
        }

        private static List<CreatorMaterialRecord> createMaterialRecords(GoldenCase goldenCase) {
            JsonNode materialsNode = goldenCase.inputSnapshot().path("inputMaterials");
            return Arrays.asList(
                    createMaterial(goldenCase.taskId(), CreatorMaterialType.TITLE_DRAFT, textOf(materialsNode, "titleDraft")),
                    createMaterial(goldenCase.taskId(), CreatorMaterialType.DESCRIPTION_DRAFT, textOf(materialsNode, "descriptionDraft")),
                    createMaterial(goldenCase.taskId(), CreatorMaterialType.MANUSCRIPT, textOf(materialsNode, "manuscript"))
            );
        }

        private static CreatorMaterialRecord createMaterial(String taskId,
                                                            CreatorMaterialType materialType,
                                                            String content) {
            CreatorMaterialRecord record = new CreatorMaterialRecord();
            record.setId(1L);
            record.setTaskId(taskId);
            record.setMaterialType(materialType.name());
            record.setContent(content);
            record.setCreateTime(LocalDateTime.now());
            record.setUpdateTime(LocalDateTime.now());
            return record;
        }

        private static String textOf(JsonNode node, String fieldName) {
            return node.path(fieldName).asText();
        }
    }

    private static class FakeCreatorSuggestionMapper implements CreatorSuggestionMapper {

        private CreatorSuggestionRecord savedRecord;

        @Override
        public int upsert(CreatorSuggestionRecord record) {
            this.savedRecord = record;
            return 1;
        }

        @Override
        public Optional<CreatorSuggestionRecord> findByTaskId(String taskId) {
            return Optional.ofNullable(savedRecord);
        }

        @Override
        public Optional<CreatorSuggestionRecord> findByTaskIdAndSessionId(String taskId, String sessionId) {
            return Optional.ofNullable(savedRecord)
                    .filter(record -> taskId.equals(record.getTaskId()))
                    .filter(record -> sessionId.equals(record.getSessionId()));
        }

        @Override
        public Optional<CreatorSuggestionRecord> findBySuggestionId(String suggestionId) {
            return Optional.ofNullable(savedRecord);
        }
    }
}
