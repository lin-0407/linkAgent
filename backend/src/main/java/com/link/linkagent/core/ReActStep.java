package com.link.linkagent.core;

/**
 * ReAct 每步的结构化载体（阶段 5.4）。
 * <p>
 * 把自研 ReAct 从「LLM 自由文本 + 正则抠 Thought/Action/Action Input/Final Answer」升级为
 * 「LLM 直接产出受 schema 约束的强类型对象」。语义与文本 ReAct 完全一致——
 * {@code finalAnswer} 非空即终止；否则用 {@code action} + {@code actionInput} 调用工具——
 * 只是把脆弱的正则解析换成 {@link com.link.linkagent.llm.LLMService#chatStructured} 的结构化解析。
 * <p>
 * 用 record：四个字段都是不可变纯数据，Jackson / BeanOutputConverter 直接按组件名映射、无需手写解析。
 *
 * @param thought     本步推理（为什么要这么做）
 * @param action      要调用的工具名；终止步为空
 * @param actionInput 工具输入（自由文本，对齐自研 Tool 的入参约定）；终止步为空
 * @param finalAnswer 终止答案；非空表示本步即给出最终回复、不再调工具
 */
public record ReActStep(String thought, String action, String actionInput, String finalAnswer) {

    /** 是否为终止步：拿到非空 finalAnswer 即结束循环。 */
    public boolean isFinal() {
        return finalAnswer != null && !finalAnswer.isBlank();
    }

    /** 是否要调用工具：非终止且给了工具名。 */
    public boolean hasAction() {
        return action != null && !action.isBlank();
    }
}
