package com.link.linkagent.creator.bilibili.model;

import java.time.LocalDateTime;

/**
 * 任务视频绑定表（creator_task_video_binding）数据库记录。
 * 每个创作任务最多绑定一个 BV。使用 JavaBean 是为了让 MyBatis 通过 setter
 * 稳定完成查询映射，避免不可变 record 的 setter 缺失问题。
 */
public class TaskVideoBindingRecord {

    private Long id;
    private String bindingId;
    private String taskId;
    private String userId;
    private String bilibiliUid;
    private String bvid;
    private String bindingStatus;
    private String verifyMessage;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    /** MyBatis 查询时先创建空对象，再逐字段调用 setter。 */
    public TaskVideoBindingRecord() {
    }

    /** 业务创建任务视频绑定时使用全参构造，保持写入字段和状态完整。 */
    public TaskVideoBindingRecord(Long id, String bindingId, String taskId, String userId,
                                  String bilibiliUid, String bvid, String bindingStatus,
                                  String verifyMessage, LocalDateTime createTime,
                                  LocalDateTime updateTime) {
        this.id = id;
        this.bindingId = bindingId;
        this.taskId = taskId;
        this.userId = userId;
        this.bilibiliUid = bilibiliUid;
        this.bvid = bvid;
        this.bindingStatus = bindingStatus;
        this.verifyMessage = verifyMessage;
        this.createTime = createTime;
        this.updateTime = updateTime;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getBindingId() { return bindingId; }
    public void setBindingId(String bindingId) { this.bindingId = bindingId; }
    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getBilibiliUid() { return bilibiliUid; }
    public void setBilibiliUid(String bilibiliUid) { this.bilibiliUid = bilibiliUid; }
    public String getBvid() { return bvid; }
    public void setBvid(String bvid) { this.bvid = bvid; }
    public String getBindingStatus() { return bindingStatus; }
    public void setBindingStatus(String bindingStatus) { this.bindingStatus = bindingStatus; }
    public String getVerifyMessage() { return verifyMessage; }
    public void setVerifyMessage(String verifyMessage) { this.verifyMessage = verifyMessage; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}
