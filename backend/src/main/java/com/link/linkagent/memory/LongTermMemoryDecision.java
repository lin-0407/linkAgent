package com.link.linkagent.memory;

/**
 * 长期记忆写入决策（设计文档 D8 / C12）。
 * <p>
 * 阶段二模型对每条候选事实给出一个动作，代码只负责校验与执行，不再让数据库唯一键替模型做判断：
 * <ul>
 *   <li>{@code ADD}：新事实，插入一条新记忆；</li>
 *   <li>{@code UPDATE}：已有事实的新版本，沿用原 id 覆盖内容（设计文档 D8 明确要求沿用原 ID）；</li>
 *   <li>{@code DELETE}：事实不再成立，把原记忆置为失效（不是用户删除，不进可恢复列表）；</li>
 *   <li>{@code NONE}：已有记忆已经覆盖，什么都不做。</li>
 * </ul>
 *
 * @param action     动作，取值 ADD / UPDATE / DELETE / NONE
 * @param id         已存在的记忆 id；ADD 与 NONE 不用
 * @param memory     新内容；ADD / UPDATE 必填
 * @param oldMemory  模型认为要被替换的原文，用于校验它不是在编造 id（设计文档 D14）
 * @param importance 新内容的重要性分数（1-100），空表示按默认值处理
 * @param reason     模型给的一句话理由，只进日志
 */
public record LongTermMemoryDecision(
        String action,
        Long id,
        String memory,
        String oldMemory,
        Integer importance,
        String reason
) {

    public static final String ACTION_ADD = "ADD";
    public static final String ACTION_UPDATE = "UPDATE";
    public static final String ACTION_DELETE = "DELETE";
    public static final String ACTION_NONE = "NONE";

    public boolean isAdd() {
        return ACTION_ADD.equalsIgnoreCase(action);
    }

    public boolean isUpdate() {
        return ACTION_UPDATE.equalsIgnoreCase(action);
    }

    public boolean isDelete() {
        return ACTION_DELETE.equalsIgnoreCase(action);
    }

    /** 是不是「不需要写库」的动作：NONE 与未识别的动作都由调用方按跳过处理。 */
    public boolean isNoop() {
        return !isAdd() && !isUpdate() && !isDelete();
    }
}
