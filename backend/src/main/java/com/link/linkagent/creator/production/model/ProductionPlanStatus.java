package com.link.linkagent.creator.production.model;

/** 制作蓝图的持久化状态。 */
public enum ProductionPlanStatus {
    GENERATING,
    READY,
    STALE,
    FAILED
}
