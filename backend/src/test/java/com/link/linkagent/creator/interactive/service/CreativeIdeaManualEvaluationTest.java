package com.link.linkagent.creator.interactive.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.link.linkagent.creator.interactive.model.InteractiveSessionRecord;
import com.link.linkagent.util.LlmJsonUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.openai.api.ResponseFormat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AI 创意建议人工偏好评测。
 *
 * 第一阶段只负责生成供作者人工评分的样本，以及在评分完成后总结风格偏好。
 * 测试直接创建独立 ChatClient，不启动 Spring 应用上下文，也不连接业务数据库和其它基础设施。
 * 两个测试均通过系统属性显式开启，避免普通 mvn test 产生真实模型费用。
 */
@EnabledIfSystemProperty(named = "linkagent.creative-idea-eval.mode", matches = "generate|summarize")
class CreativeIdeaManualEvaluationTest {

    private static final int QUESTION_COUNT = 10;
    private static final int ANSWER_COUNT_PER_QUESTION = 3;
    private static final int MAX_GENERATION_ATTEMPTS = 3;
    private static final int MAX_PARALLEL_QUESTIONS = 10;
    private static final int MAX_ANALYSIS_DATA_CHARS = 50000;
    private static final String OVERWRITE_PROPERTY = "linkagent.creative-idea-eval.overwrite";
    private static final String PREFERENCE_ANALYSIS_SYSTEM_PROMPT = """
            你是 AI 创意建议人工评分分析 Agent。
            你的职责是从作者的人工分数中归纳风格偏好，并对创意生成系统提示词提出修改建议。
            你不能调用工具，不能擅自修改评分，也不能把相关性描述成确定因果。
            最终必须只输出任务要求的 JSON 对象。
            """;
    private static final Path DOT_ENV_PATH_FROM_BACKEND = Path.of("..", ".env");
    private static final Path DOT_ENV_PATH_FROM_ROOT = Path.of(".env");
    private static final Path INPUT_PATH_FROM_BACKEND = Path.of(
            "..", "docs", "develop", "creative_idea_manual_eval_v1_inputs.json"
    );
    private static final Path INPUT_PATH_FROM_ROOT = Path.of(
            "docs", "develop", "creative_idea_manual_eval_v1_inputs.json"
    );
    private static final Path RESULT_PATH_FROM_BACKEND = Path.of(
            "..", "docs", "develop", "creative_idea_manual_eval_v1_results.json"
    );
    private static final Path RESULT_PATH_FROM_ROOT = Path.of(
            "docs", "develop", "creative_idea_manual_eval_v1_results.json"
    );
    private static final Path SUMMARY_PATH_FROM_BACKEND = Path.of(
            "..", "docs", "develop", "creative_idea_manual_eval_v1_summary.json"
    );
    private static final Path SUMMARY_PATH_FROM_ROOT = Path.of(
            "docs", "develop", "creative_idea_manual_eval_v1_summary.json"
    );

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private ChatClient chatClient;
    private ModelConfig modelConfig;

    /**
     * 并发生成待人工评分的创意回答。
     *
     * 10 个问题之间并发执行，是为了缩短真实模型评测耗时；同一问题的 3 版回答顺序执行，
     * 是为了把最大并发限制在 10，避免一次发出 30 个请求触发模型服务限流。
     * 生成结果无论成功或失败都会先写入 JSON，方便作者查看原始问题；随后再断言失败项，
     * 避免测试失败时连排查材料也没有留下。
     */
    @Test
    @EnabledIfSystemProperty(named = "linkagent.creative-idea-eval.mode", matches = "generate")
    void shouldGenerateThreeAnswersForTenQuestions() throws Exception {
        initializeStandaloneClient();
        Path resultPath = resolveWritablePath(RESULT_PATH_FROM_BACKEND, RESULT_PATH_FROM_ROOT);
        assertResultCanBeWritten(resultPath);

        EvaluationInputDataset inputDataset = readInputDataset();
        assertThat(inputDataset.questions())
                .as("人工评测输入必须恰好包含10个问题")
                .hasSize(QUESTION_COUNT);

        String creativeSystemPrompt = CreatorInteractiveService.buildCreativeSystemPrompt();
        List<QuestionEvaluation> questionEvaluations = generateInParallel(
                inputDataset.questions(),
                creativeSystemPrompt
        );
        EvaluationResultDataset resultDataset = new EvaluationResultDataset(
                inputDataset.datasetVersion(),
                LocalDateTime.now().toString(),
                creativeSystemPrompt,
                questionEvaluations
        );

        writeJson(resultPath, resultDataset);
        assertThat(questionEvaluations).hasSize(QUESTION_COUNT);
        assertThat(questionEvaluations)
                .allSatisfy(question -> assertThat(question.answers()).hasSize(ANSWER_COUNT_PER_QUESTION));
        List<String> failedAnswers = questionEvaluations.stream()
                .flatMap(question -> question.answers().stream()
                        .filter(answer -> answer.response() == null || answer.error() != null)
                        .map(answer -> question.question().questionId() + "-v" + answer.answerVersion()))
                .toList();
        assertThat(failedAnswers)
                .as("以下回答生成或结构校验失败，详细错误已保留在结果 JSON 中")
                .isEmpty();
    }

    /**
     * 读取作者完成评分的回答，调用一次 Agent 总结作者偏好并提出提示词优化建议。
     *
     * 总结前强制检查全部 30 个 score，是为了防止 Agent 把“尚未评分”误判成低分。
     * 传给 Agent 的数据只保留创意、标题、简介等分析所需字段，避免完整原始响应超过提示词长度保护。
     */
    @Test
    @EnabledIfSystemProperty(named = "linkagent.creative-idea-eval.mode", matches = "summarize")
    void shouldSummarizeHumanScoresAndSuggestPromptImprovements() throws Exception {
        initializeStandaloneClient();
        Path resultPath = resolveExistingPath(RESULT_PATH_FROM_BACKEND, RESULT_PATH_FROM_ROOT);
        EvaluationResultDataset resultDataset = evaluationObjectMapper()
                .readValue(resultPath.toFile(), EvaluationResultDataset.class);
        validateManualScores(resultDataset);

        String creativeSystemPrompt = CreatorInteractiveService.buildCreativeSystemPrompt();
        String compactEvaluationData = buildCompactEvaluationData(resultDataset);
        assertThat(compactEvaluationData.length())
                .as("评分数据过长会超过 Agent 提示词预算，请缩短样本回答后重试")
                .isLessThanOrEqualTo(MAX_ANALYSIS_DATA_CHARS);

        String taskMessage = buildPreferenceAnalysisTask(creativeSystemPrompt, compactEvaluationData);
        String agentAnswer = callModel(PREFERENCE_ANALYSIS_SYSTEM_PROMPT, taskMessage);
        assertThat(agentAnswer).as("偏好总结 Agent 没有返回最终答案").isNotBlank();

        JsonNode parsedSummary = tryParseJson(agentAnswer);
        EvaluationSummary summary = new EvaluationSummary(
                resultDataset.datasetVersion(),
                LocalDateTime.now().toString(),
                resultPath.toString(),
                creativeSystemPrompt,
                parsedSummary,
                agentAnswer
        );
        writeJson(resolveWritablePath(SUMMARY_PATH_FROM_BACKEND, SUMMARY_PATH_FROM_ROOT), summary);
    }

    /**
     * 按问题维度并发生成评测结果，并按 questionId 恢复稳定顺序。
     *
     * 并发完成顺序是不确定的，写文件前重新排序可以保证多次评测的 JSON 结构稳定，
     * 方便作者逐项评分和对比不同轮次的结果。
     */
    private List<QuestionEvaluation> generateInParallel(List<EvaluationQuestion> questions,
                                                        String creativeSystemPrompt) {
        int parallelism = Math.min(MAX_PARALLEL_QUESTIONS, questions.size());
        try (ExecutorService executorService = Executors.newFixedThreadPool(parallelism)) {
            List<CompletableFuture<QuestionEvaluation>> futures = questions.stream()
                    .map(question -> CompletableFuture.supplyAsync(
                            () -> generateQuestionEvaluation(question, creativeSystemPrompt),
                            executorService
                    ))
                    .toList();
            return futures.stream()
                    .map(CompletableFuture::join)
                    .sorted(Comparator.comparing(result -> result.question().questionId()))
                    .toList();
        }
    }

    /**
     * 为单个问题构建与生产链路一致的用户提示词，并连续生成 3 版回答。
     *
     * 用户提示词只构建一次，保证同一问题的三版回答输入完全一致，差异只来自模型生成过程。
     */
    private QuestionEvaluation generateQuestionEvaluation(EvaluationQuestion question,
                                                          String creativeSystemPrompt) {
        InteractiveSessionRecord session = toSessionRecord(question);
        String userPrompt = CreatorInteractiveService.buildCreativeUserPrompt(
                session,
                question.extraRequirement()
        );
        List<AnswerEvaluation> answers = new ArrayList<>();
        for (int answerVersion = 1; answerVersion <= ANSWER_COUNT_PER_QUESTION; answerVersion++) {
            answers.add(generateAnswer(answerVersion, creativeSystemPrompt, userPrompt));
        }
        return new QuestionEvaluation(question, answers);
    }

    /**
     * 调用真实模型并保存结构化回答、模型信息和耗时。
     *
     * 模型偶尔会返回语法错误 JSON 或少于 3 个方向，因此单份回答最多尝试 3 次。
     * 全部尝试失败时不会向外抛出，而是转换成带 error 和 rawOutput 的回答记录，
     * 这样整批结果仍能落盘，作者可以直接看到最后一次失败模型实际返回了什么。
     */
    private AnswerEvaluation generateAnswer(int answerVersion,
                                            String creativeSystemPrompt,
                                            String userPrompt) {
        long startNanos = System.nanoTime();
        String lastRawOutput = null;
        JsonNode lastResponse = null;
        String lastError = null;
        for (int attempt = 1; attempt <= MAX_GENERATION_ATTEMPTS; attempt++) {
            lastRawOutput = null;
            lastResponse = null;
            try {
                lastRawOutput = callModel(creativeSystemPrompt, userPrompt);
                lastResponse = objectMapper.readTree(LlmJsonUtil.extractJsonObject(lastRawOutput));
                lastError = validateCreativeResponse(lastResponse);
                if (lastError == null) {
                    return new AnswerEvaluation(
                            answerVersion,
                            modelConfig.modelName(),
                            attempt,
                            elapsedMillis(startNanos),
                            lastResponse,
                            null,
                            null,
                            null
                    );
                }
            } catch (RuntimeException | IOException exception) {
                lastResponse = null;
                lastError = exception.getMessage();
            }
        }
        return new AnswerEvaluation(
                answerVersion,
                modelConfig.modelName(),
                MAX_GENERATION_ATTEMPTS,
                elapsedMillis(startNanos),
                lastResponse,
                lastResponse == null ? lastRawOutput : null,
                null,
                lastError
        );
    }

    /**
     * 从根目录 .env 创建仅包含 OpenAI 兼容模型客户端的独立测试环境。
     *
     * 这里不启动 Spring 容器，因此不会创建 MyBatis、Redis、Milvus 或任何业务 Bean；
     * 测试只复用项目已经锁定版本的 Spring AI 客户端发起 HTTP 请求。
     */
    private void initializeStandaloneClient() throws IOException {
        if (chatClient != null) {
            return;
        }
        Map<String, String> dotEnv = readDotEnv();
        modelConfig = new ModelConfig(
                requireConfig(dotEnv, "LLM_API_KEY"),
                requireConfig(dotEnv, "LLM_BASE_URL"),
                requireConfig(dotEnv, "LLM_MODEL")
        );
        OpenAiApi openAiApi = OpenAiApi.builder()
                .apiKey(modelConfig.apiKey())
                .baseUrl(modelConfig.baseUrl())
                .build();
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(modelConfig.modelName())
                .responseFormat(new ResponseFormat(ResponseFormat.Type.JSON_OBJECT, null))
                .build();
        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(options)
                .build();
        chatClient = ChatClient.create(chatModel);
    }

    /**
     * 使用独立 ChatClient 调用一次模型 API。
     *
     * 生成阶段和偏好总结阶段共用同一调用边界，确保它们都不经过数据库或运行期设置服务。
     */
    private String callModel(String systemPrompt, String userPrompt) {
        return chatClient.prompt()
                .system(systemPrompt)
                .user(userPrompt)
                .call()
                .content();
    }

    /**
     * 读取项目根目录 .env，并按标准 key=value 规则解析配置。
     *
     * 使用第一个等号切分，避免 API Key 或 URL 中包含等号时被截断；空行和注释不会进入配置。
     */
    private Map<String, String> readDotEnv() throws IOException {
        Path dotEnvPath = resolveExistingPath(DOT_ENV_PATH_FROM_BACKEND, DOT_ENV_PATH_FROM_ROOT);
        Map<String, String> values = new LinkedHashMap<>();
        for (String line : Files.readAllLines(dotEnvPath, StandardCharsets.UTF_8)) {
            String normalized = line.trim();
            if (normalized.isEmpty() || normalized.startsWith("#")) {
                continue;
            }
            int separatorIndex = normalized.indexOf('=');
            if (separatorIndex <= 0) {
                continue;
            }
            String key = normalized.substring(0, separatorIndex).trim();
            String value = stripOptionalQuotes(normalized.substring(separatorIndex + 1).trim());
            values.put(key, value);
        }
        return values;
    }

    /**
     * 优先使用当前进程环境变量，未设置时回退到 .env，并拒绝缺失的模型配置。
     *
     * 这种顺序允许作者临时切换评测模型，同时避免把密钥硬编码进测试源码。
     */
    private String requireConfig(Map<String, String> dotEnv, String key) {
        String processValue = System.getenv(key);
        String value = processValue == null || processValue.isBlank() ? dotEnv.get(key) : processValue;
        assertThat(value).as("缺少评测模型配置：" + key).isNotBlank();
        return value.trim();
    }

    /**
     * 去除 .env 值两侧成对的单引号或双引号。
     *
     * 只处理完整成对引号，不修改值内部字符，避免破坏 URL、Key 等真实配置。
     */
    private String stripOptionalQuotes(String value) {
        if (value.length() < 2) {
            return value;
        }
        char first = value.charAt(0);
        char last = value.charAt(value.length() - 1);
        if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    /**
     * 将纳秒计时转换为毫秒，保留每份回答的实际 API 等待时间。
     */
    private long elapsedMillis(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    /**
     * 校验回答是否满足创意方向卡的最小结构契约。
     *
     * 本阶段不自动判断内容好坏，只确认 options 存在且恰好有 3 个；内容质量由作者填写 score。
     */
    private String validateCreativeResponse(JsonNode response) {
        JsonNode options = response.path("options");
        if (!options.isArray()) {
            return "回答缺少 options 数组";
        }
        if (options.size() != 3) {
            return "options 数量不是3，实际为" + options.size();
        }
        return null;
    }

    /**
     * 检查人工评分是否完整且位于 0 到 10 分之间。
     *
     * 空分数不能直接交给总结 Agent，否则 Agent 无法区分“未评分”和“作者不喜欢”。
     */
    private void validateManualScores(EvaluationResultDataset resultDataset) {
        List<String> missingScores = new ArrayList<>();
        List<String> invalidScores = new ArrayList<>();
        for (QuestionEvaluation question : resultDataset.questions()) {
            for (AnswerEvaluation answer : question.answers()) {
                String answerId = question.question().questionId() + "-v" + answer.answerVersion();
                if (answer.score() == null) {
                    missingScores.add(answerId);
                } else if (answer.score() < 0 || answer.score() > 10) {
                    invalidScores.add(answerId + "=" + answer.score());
                }
            }
        }
        assertThat(missingScores)
                .as("请先为全部30份回答填写0到10分的 score 字段")
                .isEmpty();
        assertThat(invalidScores)
                .as("score 必须位于0到10之间")
                .isEmpty();
    }

    /**
     * 从完整评测结果中提取偏好分析真正需要的数据。
     *
     * 完整文件还包含 Token、耗时和原始错误等字段，全部传给 Agent 会浪费上下文；
     * 这里只保留问题、分数、创意名称、标题骨架、简介骨架、亮点和推荐理由。
     */
    private String buildCompactEvaluationData(EvaluationResultDataset resultDataset) throws IOException {
        List<CompactQuestionEvaluation> compactQuestions = resultDataset.questions().stream()
                .map(question -> new CompactQuestionEvaluation(
                        question.question().questionId(),
                        question.question().idea(),
                        question.question().videoType(),
                        question.answers().stream().map(this::toCompactAnswer).toList()
                ))
                .toList();
        return evaluationObjectMapper().writeValueAsString(compactQuestions);
    }

    /**
     * 将一版完整回答转换为供偏好总结使用的紧凑结构。
     *
     * 回答失败时保留 error，即使作者给失败回答打 0 分，总结 Agent 也能区分内容低质和调用故障。
     */
    private CompactAnswerEvaluation toCompactAnswer(AnswerEvaluation answer) {
        List<CompactCreativeOption> options = new ArrayList<>();
        if (answer.response() != null && answer.response().path("options").isArray()) {
            for (JsonNode option : answer.response().path("options")) {
                options.add(new CompactCreativeOption(
                        option.path("optionName").asText(""),
                        option.path("titleOutline"),
                        option.path("descriptionOutline"),
                        option.path("sellingPoints"),
                        option.path("recommendReason").asText("")
                ));
            }
        }
        return new CompactAnswerEvaluation(
                answer.answerVersion(),
                answer.score(),
                options,
                answer.error()
        );
    }

    /**
     * 组装偏好总结 Agent 的一次性任务说明。
     *
     * 当前生产系统提示词作为被分析对象传入，而不是复制到测试常量中，
     * 确保每次总结都针对真实生效的提示词提出建议。
     */
    private String buildPreferenceAnalysisTask(String creativeSystemPrompt,
                                               String compactEvaluationData) {
        return """
                这是一次人工偏好分析任务，不需要调用任何工具。

                下面是当前想法扩展 Agent 的系统提示词：
                <creative_system_prompt>
                %s
                </creative_system_prompt>

                下面是作者已经人工评分的回答。score 为0到10分，分数越高表示作者越喜欢整份回答：
                <scored_answers>
                %s
                </scored_answers>

                请分析高分和低分回答在创意方向、标题骨架、简介结构上的共同模式。
                单一总分只能说明整体偏好，不要把相关性武断解释为某个单字段的确定因果。
                所有判断必须引用 questionId 和 answerVersion 作为依据。

                最终只输出一个 JSON 对象，字段固定如下：
                {
                  "preferenceSummary": "作者整体风格偏好总结",
                  "likedPatterns": ["高分回答的共同模式"],
                  "dislikedPatterns": ["低分回答的共同模式"],
                  "ideaPreferences": ["创意方向偏好"],
                  "titlePreferences": ["标题骨架偏好"],
                  "descriptionPreferences": ["简介结构偏好"],
                  "evidence": ["questionId-v版本号：具体依据"],
                  "currentPromptProblems": ["当前系统提示词的问题"],
                  "promptOptimizationSuggestions": ["可执行的提示词优化建议"],
                  "proposedSystemPrompt": "基于评分建议的新系统提示词草案"
                }
                """.formatted(creativeSystemPrompt, compactEvaluationData);
    }

    /**
     * 尝试把总结 Agent 的最终答案解析成 JSON。
     *
     * 如果模型没有遵守 JSON 约定则返回 null，同时外层仍保存 rawOutput，避免丢失可人工查看的总结文本。
     */
    private JsonNode tryParseJson(String rawOutput) {
        try {
            return objectMapper.readTree(LlmJsonUtil.extractJsonObject(rawOutput));
        } catch (IOException | IllegalArgumentException exception) {
            return null;
        }
    }

    /**
     * 读取固定的 10 条创意问题输入。
     *
     * 输入与生成结果分文件保存，避免重新生成回答时意外修改评测问题本身。
     */
    private EvaluationInputDataset readInputDataset() throws IOException {
        Path path = resolveExistingPath(INPUT_PATH_FROM_BACKEND, INPUT_PATH_FROM_ROOT);
        return evaluationObjectMapper().readValue(path.toFile(), EvaluationInputDataset.class);
    }

    /**
     * 把文件中的评测问题转换为生产 Prompt 构建方法需要的会话对象。
     *
     * 这里只构造内存对象，不写业务数据库；评测因此不会产生虚假的创作任务和方向卡记录。
     */
    private InteractiveSessionRecord toSessionRecord(EvaluationQuestion question) {
        InteractiveSessionRecord session = new InteractiveSessionRecord();
        session.setSessionId("eval-" + question.questionId());
        session.setTaskId("eval-" + question.questionId());
        session.setIdea(question.idea());
        session.setVideoType(question.videoType());
        session.setBackgroundContext(question.backgroundContext());
        session.setUnderstandingSummary(question.understandingSummary());
        return session;
    }

    /**
     * 防止生成测试覆盖已经填写过人工分数的结果文件。
     *
     * 只有作者显式设置 overwrite 系统属性时才允许覆盖，避免一次误操作丢失 30 份人工评分。
     */
    private void assertResultCanBeWritten(Path resultPath) {
        if (Files.exists(resultPath) && !Boolean.getBoolean(OVERWRITE_PROPERTY)) {
            throw new IllegalStateException(
                    "评测结果文件已存在，为避免覆盖人工评分，请先备份文件；确认覆盖时增加 -D"
                            + OVERWRITE_PROPERTY + "=true"
            );
        }
    }

    /**
     * 兼容从 backend 目录或项目根目录执行测试时的文件定位方式。
     *
     * 优先使用 backend 相对路径，与 Maven 的常规执行目录一致；找不到时再尝试项目根目录路径。
     */
    private Path resolveExistingPath(Path backendRelativePath, Path rootRelativePath) {
        Path backendPath = backendRelativePath.toAbsolutePath().normalize();
        if (Files.exists(backendPath)) {
            return backendPath;
        }
        Path rootPath = rootRelativePath.toAbsolutePath().normalize();
        assertThat(rootPath).as("评测文件必须存在").exists();
        return rootPath;
    }

    /**
     * 选择评测输出文件的写入位置。
     *
     * 通过父目录是否存在判断当前工作目录，保证无论作者从 backend 还是项目根目录执行，
     * 结果最终都写入同一个 docs/develop 目录。
     */
    private Path resolveWritablePath(Path backendRelativePath, Path rootRelativePath) {
        Path backendPath = backendRelativePath.toAbsolutePath().normalize();
        if (Files.exists(backendPath.getParent())) {
            return backendPath;
        }
        return rootRelativePath.toAbsolutePath().normalize();
    }

    /**
     * 使用 UTF-8 和格式化 JSON 写入人工可编辑文件。
     *
     * 格式化输出让作者能直接定位每个 score 字段；覆盖策略由调用方在写入前单独控制。
     */
    private void writeJson(Path path, Object value) throws IOException {
        Files.createDirectories(path.getParent());
        String json = evaluationObjectMapper()
                .writerWithDefaultPrettyPrinter()
                .writeValueAsString(value);
        Files.writeString(
                path,
                json,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
        );
    }

    /**
     * 创建评测文件专用 ObjectMapper 副本。
     *
     * 强制保留 null 字段，确保生成文件中明确出现 `score: null`，作者不需要手工补字段名。
     * 使用副本是为了不修改应用全局 ObjectMapper 的序列化行为。
     */
    private ObjectMapper evaluationObjectMapper() {
        return objectMapper.copy().setSerializationInclusion(JsonInclude.Include.ALWAYS);
    }

    private record EvaluationInputDataset(String datasetVersion, List<EvaluationQuestion> questions) {
    }

    private record EvaluationQuestion(
            String questionId,
            String idea,
            String videoType,
            String extraRequirement,
            String backgroundContext,
            String understandingSummary
    ) {
    }

    private record EvaluationResultDataset(
            String datasetVersion,
            String generatedAt,
            String creativeSystemPrompt,
            List<QuestionEvaluation> questions
    ) {
    }

    private record QuestionEvaluation(EvaluationQuestion question, List<AnswerEvaluation> answers) {
    }

    private record AnswerEvaluation(
            int answerVersion,
            String modelName,
            Integer attemptCount,
            Long elapsedMs,
            JsonNode response,
            String rawOutput,
            Integer score,
            String error
    ) {
    }

    private record CompactQuestionEvaluation(
            String questionId,
            String idea,
            String videoType,
            List<CompactAnswerEvaluation> answers
    ) {
    }

    private record CompactAnswerEvaluation(
            int answerVersion,
            Integer score,
            List<CompactCreativeOption> options,
            String error
    ) {
    }

    private record CompactCreativeOption(
            String optionName,
            JsonNode titleOutline,
            JsonNode descriptionOutline,
            JsonNode sellingPoints,
            String recommendReason
    ) {
    }

    private record EvaluationSummary(
            String datasetVersion,
            String generatedAt,
            String sourceResultFile,
            String creativeSystemPrompt,
            JsonNode analysis,
            String rawOutput
    ) {
    }

    /**
     * 独立模型客户端只需要的三项配置，不包含数据库或其他基础设施信息。
     */
    private record ModelConfig(String apiKey, String baseUrl, String modelName) {
    }
}
