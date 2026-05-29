package com.link.linkagent.creator.workflow.model;

import java.time.LocalDateTime;

/**
 * 工作流消息数据库记录。
 * 消息先落库再展示，保证后续 SSE 中断后仍能从历史消息恢复页面状态。
 */
public class CreatorWorkflowMessageRecord {

    private Long id;
    private String messageId;
    private String sessionId;
    private String role;
    private String content;
    private String contentType;
    private String detailRefType;
    private String detailRefId;
    private Integer sequenceNo;
    private LocalDateTime createTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getMessageId() {
        return messageId;
    }

    public void setMessageId(String messageId) {
        this.messageId = messageId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
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

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public String getDetailRefType() {
        return detailRefType;
    }

    public void setDetailRefType(String detailRefType) {
        this.detailRefType = detailRefType;
    }

    public String getDetailRefId() {
        return detailRefId;
    }

    public void setDetailRefId(String detailRefId) {
        this.detailRefId = detailRefId;
    }

    public Integer getSequenceNo() {
        return sequenceNo;
    }

    public void setSequenceNo(Integer sequenceNo) {
        this.sequenceNo = sequenceNo;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }
}
