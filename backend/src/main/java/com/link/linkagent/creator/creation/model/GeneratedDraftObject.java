package com.link.linkagent.creator.creation.model;

/** 对象键由后端按任务和执行尝试生成，不能由工程 JSON 指定。 */
public record GeneratedDraftObject(String name, String objectKey, String contentType, long bytes, String sha256) { }
