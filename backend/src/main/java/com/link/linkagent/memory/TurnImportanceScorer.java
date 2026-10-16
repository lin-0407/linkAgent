package com.link.linkagent.memory;

import com.link.linkagent.llm.LLMService;
import com.link.linkagent.llm.usage.LlmUsageContext;
import com.link.linkagent.prompt.service.PromptService;
import com.link.linkagent.util.TextUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 单轮对话重要性打分器 —— 给每一轮对话打一个 1-100 的分数，供预算累加器扣减（设计文档 C2 / D17.2 / Q3）。
 *
 * <h3>为什么每轮单独打一次分</h3>
 * 递减预算模型要求「每轮扣这一轮的分数」，而这个分数在触发提取之前拿不到：
 * 如果等批量抽取时才打分，扣减就没有对象了。作者 2026-09 确认：每轮同步打一次分，
 * 这一次调用替掉原来每轮一次的抽取调用，整体调用次数不增加，但抽取从「每轮一次」降到「每 N 轮一次」。
 *
 * <h3>失败处理</h3>
 * 打分调用失败不阻断任何东西：返回兜底分 {@value #FALLBACK_IMPORTANCE}，预算照常扣减。
 * 否则一次模型抖动就会让预算永远停在原地，长期记忆再也不触发。
 */
@Component
public class TurnImportanceScorer {

    private static final Logger log = LoggerFactory.getLogger(TurnImportanceScorer.class);

    /** 打分失败或没给出可用数字时的兜底分：沿用 D17.2 的兜底语义，按 1-100 口径取 40。 */
    static final int FALLBACK_IMPORTANCE = 40;

    /**
     * 打分调用的等待上限：30 秒。
     * <p>
     * 不能沿用压缩链路的 5 分钟（{@link MemoryCallTimeout#DEFAULT_TIMEOUT_SECONDS}）：这个方法在
     * 「回答已经生成完、只等 done 事件」的同步位置上，2026-09-19 联调时一次打分就等了 4 秒，
     * 界面在这期间一直停在「生成中」。30 秒足够正常调用返回，超时按失败处理（兜底 40 分）——
     * 分数保守一点没关系，不能让界面被这一步拖住。
     */
    static final long TIMEOUT_SECONDS = 30;

    /**
     * 打分调用显式关闭思考（作者 2026-09-19 确认）。
     * <p>
     * 打分只是一次分类判断：给一轮对话打一个 1-100 的整数。开着思考时实测每次 4~9 秒、几百到上千输出 token，
     * 偶尔还会绕很久；关掉思考后响应快、结果更稳定。作者同时确认只对打分降级——
     * 摘要生成、复核、事实点抽取这些影响内容的调用保持思考，避免影响效果。
     */
    static final boolean DISABLE_THINKING = true;

    /** 打分调用的输出上限：一个整数分加一句理由，512 token 足够，顺便封住思考跑飞的风险。 */
    static final int MAX_OUTPUT_TOKENS = 512;

    private static final int MIN_IMPORTANCE = 1;
    private static final int MAX_IMPORTANCE = 100;

    private final LLMService llmService;
    private final PromptService promptService;
    private final long timeoutSeconds;

    /**
     * 生产构造器。
     * <p>
     * 必须显式标 @Autowired：类里还有下面那个单测用的超时构造器，Spring 在有多个构造器时
     * 找不到默认构造器会直接启动失败（2026-09-19 加超时构造器时踩到，上下文测试报 No default constructor found）。
     */
    @Autowired
    public TurnImportanceScorer(LLMService llmService, PromptService promptService) {
        this(llmService, promptService, TIMEOUT_SECONDS);
    }

    /** 可指定超时的构造，供单测用短超时验证「卡住的打分调用按失败兜底」。 */
    TurnImportanceScorer(LLMService llmService, PromptService promptService, long timeoutSeconds) {
        this.llmService = llmService;
        this.promptService = promptService;
        this.timeoutSeconds = Math.max(1, timeoutSeconds);
    }

    /**
     * 给一轮对话打分。
     *
     * @param userMessage 用户本轮输入
     * @param finalAnswer Agent 本轮输出的最终答案
     * @return 1-100 的整数分；调用失败或输出不合法时返回 {@link #FALLBACK_IMPORTANCE}
     */
    public int score(String userMessage, String finalAnswer) {
        String systemPrompt;
        String userPrompt;
        try {
            systemPrompt = promptService.get("long_term_memory_importance.system");
            userPrompt = promptService.render("long_term_memory_importance.user", Map.of(
                    "userMessage", TextUtil.trimToDefault(userMessage, ""),
                    "finalAnswer", TextUtil.trimToDefault(finalAnswer, "")
            ));
        } catch (RuntimeException promptException) {
            // 提示词种子缺失是部署错误（必须重新执行 init.sql），不会自愈，单独打 ERROR 便于当场发现。
            log.error("重要性打分提示词缺失或渲染失败，请确认已重新执行 init.sql 灌入 long_term_memory_importance 种子，error={}",
                    promptException.getMessage());
            return FALLBACK_IMPORTANCE;
        }
        try (LlmUsageContext.UsageScope ignored = LlmUsageContext.scene("长期记忆重要性打分")) {
            // 30 秒兜底：打分在「回答已生成、只等 done」的同步位置上，卡住的调用会直接顶住界面。
            TurnImportanceScore output = MemoryCallTimeout.call("长期记忆重要性打分", timeoutSeconds,
                    TimeUnit.SECONDS,
                    () -> llmService.chatStructured(systemPrompt, userPrompt, TurnImportanceScore.class,
                            DISABLE_THINKING, MAX_OUTPUT_TOKENS));
            int score = normalize(output == null ? null : output.importance());
            log.info("长期记忆重要性打分：score={}, reason={}", score,
                    TextUtil.abbreviate(TextUtil.trimToDefault(output == null ? null : output.reason(), ""), 60));
            return score;
        } catch (Exception exception) {
            log.warn("长期记忆重要性打分失败，按兜底 {} 分计入预算，error={}", FALLBACK_IMPORTANCE, exception.getMessage());
            return FALLBACK_IMPORTANCE;
        }
    }

    /**
     * 分数收敛：1-100 之外按边界截断，没给分数或给了非正数用兜底分。
     * 不直接信任模型返回的数字，避免 0 分（永远不触发）或 150 分（每轮都触发）把预算搞乱。
     */
    int normalize(Integer importance) {
        if (importance == null || importance <= 0) {
            return FALLBACK_IMPORTANCE;
        }
        return Math.min(MAX_IMPORTANCE, Math.max(MIN_IMPORTANCE, importance));
    }
}
