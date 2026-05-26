package com.link.linkagent.creator.task.model;

/**
 * 创作材料类型。
 * 使用枚举约束材料范围，是为了让后续 Agent 明确知道每段文本在创作流程中的业务含义。
 */
public enum CreatorMaterialType {

    TITLE_DRAFT,
    DESCRIPTION_DRAFT,
    MANUSCRIPT,
    SUBTITLE
}
