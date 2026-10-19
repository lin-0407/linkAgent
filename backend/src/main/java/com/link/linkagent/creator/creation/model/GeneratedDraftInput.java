package com.link.linkagent.creator.creation.model;

/** 整片授权冻结的制作依据；工程摘要不包含每次执行才生成的运行和尝试标识。 */
public record GeneratedDraftInput(String publishingSessionId, String publishingResultId, String productionPlanId,
                                  String baseVersionId, String storyboardId, String renderContentHash) { }
