package com.link.linkagent.creator.creation.model;

/** 清单中固定文件的事实，不包含可由调用方选择的存储路径。 */
public record GeneratedDraftFile(String name, String contentType, long bytes, String sha256) { }
