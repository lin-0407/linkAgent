package com.link.linkagent.core;

import com.link.linkagent.util.TextUtil;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Agent 执行模式路由器 —— 根据请求模式和用户消息内容，决定走 REACT / PLAN_EXECUTE / MULTI_AGENT。
 * <p>
 * <b>核心设计决策：规则路由而非模型路由</b>
 * <p>
 * 默认 AUTO 不额外调用 LLM 做模式选择，而是用轻量规则（关键词匹配 + 消息长度）判断任务复杂度。
 * 这样做的三个原因：
 * <ol>
 *   <li><b>零额外成本</b>：不会为了"选择模式"先产生一次 LLM 调用</li>
 *   <li><b>确定性</b>：规则路由结果可预测、可调试，不受模型随机性影响</li>
 *   <li><b>防过度编排</b>：避免路由模型误判导致简单问题（如"你好"）走进 Plan & Execute 的冗长编排流程</li>
 * </ol>
 * <p>
 * <b>路由决策优先级</b>（从高到低）：
 * <ol>
 *   <li>调用方显式指定模式（非 AUTO）→ 直接使用</li>
 *   <li>命中 MULTI_AGENT 关键词 → 走多智能体协作</li>
 *   <li>命中 PLAN 关键词 或 消息长度 >= 阈值 → 走 Plan & Execute</li>
 *   <li>以上都不满足 → 走 ReAct（默认、最轻量）</li>
 * </ol>
 * <p>
 * MULTI_AGENT 优先级高于 PLAN_EXECUTE 的原因：MULTI_AGENT 任务通常更复杂（需要多视角协作），
 * 且其关键词更具体、误触发率更低，先匹配可以避免 PLAN_EXECUTE 错误拦截。
 */
@Component
public class AgentExecutionModeRouter {

    /**
     * 消息长度阈值：超过此长度被认为任务足够复杂，需要 Plan & Execute 模式做规划。
     * <p>
     * 120 字符的选择依据：常规聊天消息通常 20~80 字符；超过 120 字符往往意味着用户在描述
     * 一个需要多步推理的复杂任务（如"帮我优化这段代码，先分析性能瓶颈，再提出改进方案，最后对比效果"）。
     * 这个阈值作为关键词匹配的兜底，能在用户未明确使用"计划""步骤"等词时仍正确触发规划模式。
     */
    private static final int PLAN_LENGTH_THRESHOLD = 120;

    /**
     * MULTI_AGENT 触发关键词集合。
     * <p>
     * 覆盖三个维度的信号：
     * <ol>
     *   <li><b>显式要求</b>："多agent""多 agent""multi agent""多智能体"——用户明确要求多智能体</li>
     *   <li><b>视角切换</b>："多视角""分别从""综合评估"——需要从不同角度分析同一问题</li>
     *   <li><b>B 站领域特征</b>："竞品""受众""评论""弹幕""复盘"——内容创作场景中常见的多维度分析需求</li>
     * </ol>
     */
    private static final List<String> MULTI_AGENT_KEYWORDS = List.of(
            "多agent", "多 agent", "multi agent", "多智能体", "多视角", "分别从", "综合评估", "竞品",
            "受众", "评论", "弹幕", "复盘"
    );

    /**
     * PLAN_EXECUTE 触发关键词集合。
     * <p>
     * <b>关键词选取原则</b>：
     * <ul>
     *   <li>信号明确且频率适中的词才入选——既不能在每个简单请求中都出现，又要在真正需要规划时命中</li>
     *   <li>"计划""步骤""拆解""方案"——直接表示需要结构化规划的词汇</li>
     *   <li>"实现""排查""优化"——复杂工程任务的典型动词</li>
     *   <li>"工作流""先做""再做""同时""多个""复杂""对比"——暗示多步依赖或并行执行</li>
     * </ul>
     * <p>
     * <b>为什么没有"分析"：</b>
     * 它在中文里频率太高（"帮我分析这段代码""分析一下这个数据"），
     * 几乎会让所有 AUTO 请求都走 PLAN_EXECUTE，浪费一次 Planner 的 LLM 调用。
     * 真正需要规划的重任务会命中更具体的词（"拆解""方案""优化"等），
     * 或通过消息长度阈值（{@link #PLAN_LENGTH_THRESHOLD}）兜底触发。
     */
    private static final List<String> PLAN_KEYWORDS = List.of(
            "计划", "步骤", "拆解", "实现", "排查", "优化", "方案", "工作流", "先做", "再做", "同时",
            "多个", "复杂", "对比"
    );

    /**
     * 路由决策入口：根据请求模式和用户消息，输出应使用的执行模式。
     * <p>
     * <b>决策流程</b>：
     * <ol>
     *   <li><b>显式模式直接透传</b>：调用方指定 REACT/PLAN_EXECUTE/MULTI_AGENT 时原样返回，不做任何规则判断。
     *       这保证了调用方的意图优先于路由规则——显式指定模式通常意味着调用方有特殊理由。</li>
     *   <li><b>MULTI_AGENT 优先匹配</b>：AUTO 模式下先检查多智能体关键词，命中则走 MULTI_AGENT。
     *       优先于 PLAN_EXECUTE 的原因是多智能体任务关键词更具体、更少误触发。</li>
     *   <li><b>PLAN_EXECUTE 匹配</b>：消息长度 >= {@link #PLAN_LENGTH_THRESHOLD} 或命中规划关键词，
     *       则走 Plan & Execute 做结构化任务拆解。</li>
     *   <li><b>默认 ReAct</b>：以上都不满足时走最简单的 ReAct 循环——最轻量的主体路径。</li>
     * </ol>
     *
     * @param requestedMode 调用方期望的执行模式（AUTO 表示"你帮我决定"；其余为显式指定）
     * @param userMessage 用户原始输入，用于 AUTO 模式下的复杂度判断
     * @return 最终选定的执行模式，保证不会返回 null
     */
    public AgentExecutionMode route(AgentExecutionMode requestedMode, String userMessage) {
        // 1. 归一化请求模式（null → AUTO），显式模式直接透传
        AgentExecutionMode normalized = AgentExecutionMode.normalize(requestedMode);
        if (normalized != AgentExecutionMode.AUTO) {
            return normalized;
        }

        // 2. AUTO 模式：小写归一化后做关键词匹配（避免大小写/空白差异）
        String normalizedMessage = TextUtil.trimToDefault(userMessage, "").toLowerCase();

        // 3. MULTI_AGENT 优先匹配：多智能体关键词更具体，误触发率低
        if (containsAny(normalizedMessage, MULTI_AGENT_KEYWORDS)) {
            return AgentExecutionMode.MULTI_AGENT;
        }

        // 4. PLAN_EXECUTE 匹配：关键词命中 或 消息足够长 → 认为任务需要规划
        //    长度阈值是兜底机制：用户可能不自觉地描述了复杂任务而未使用规划关键词
        if (normalizedMessage.length() >= PLAN_LENGTH_THRESHOLD || containsAny(normalizedMessage, PLAN_KEYWORDS)) {
            return AgentExecutionMode.PLAN_EXECUTE;
        }

        // 5. 默认 REACT：最轻量、最稳定的路径
        return AgentExecutionMode.REACT;
    }

    /**
     * 判断字符串是否包含关键词列表中的任意一个。
     * <p>
     * 使用 String#contains 做子串匹配（而非正则或分词），兼顾性能和灵活性：
     * "多agent"可以匹配"用多agent帮我分析"、"多 agent"可以匹配"多 agent 协作"。
     * <p>
     * 关键词列表通常很小（10~20 个），线性扫描 O(n*k) 足够快，不需要 Trie 等复杂优化。
     *
     * @param value 待检查的字符串（调用方已做小写归一化）
     * @param keywords 关键词列表
     * @return true 表示命中至少一个关键词
     */
    private boolean containsAny(String value, List<String> keywords) {
        for (String keyword : keywords) {
            if (value.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
