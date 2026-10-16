package com.link.linkagent.knowledge.model;

/** 只保留待删文档 ID，不保留已过期的视频正文；外部索引失败后仍可重试物理清理。 */
public record ReferenceIndexCleanupRecord(String videoId, String itemIds, String chunkIds) { }
