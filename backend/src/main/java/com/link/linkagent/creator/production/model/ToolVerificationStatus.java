package com.link.linkagent.creator.production.model;

/** 工具资料的可信状态；没有可靠来源时禁止生成菜单级操作。 */
public enum ToolVerificationStatus {
    VERIFIED,
    SOURCE_REQUIRED,
    STALE,
    FAILED
}
