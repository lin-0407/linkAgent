package com.link.linkagent.memory.model;

import java.time.LocalDateTime;

/**
 * 会话消息记录。
 * <p>
 * 消息表保存完整问答原文，避免短期记忆裁剪或 Redis 失效后无法追溯历史对话。
 */
public class ConversationMessageRecord {

    private Long id;
    private String sessionId;
    /**
     * 归属用户。
     * <p>
     * 表里本来只能靠 session_id 关联会话表才能知道消息属于谁，长期记忆按用户捞增量时每次都要 JOIN；
     * 这里冗余一份，让增量查询直接走 (user_id, id) 索引（设计文档 C5）。
     */
    private String userId;
    private String role;
    private String content;
    private String toolName;
    private Integer tokenCount;
    private LocalDateTime createTime;
    private Integer isDeleted;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getToolName() {
        return toolName;
    }

    public void setToolName(String toolName) {
        this.toolName = toolName;
    }

    public Integer getTokenCount() {
        return tokenCount;
    }

    public void setTokenCount(Integer tokenCount) {
        this.tokenCount = tokenCount;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    public Integer getIsDeleted() {
        return isDeleted;
    }

    public void setIsDeleted(Integer isDeleted) {
        this.isDeleted = isDeleted;
    }
}
