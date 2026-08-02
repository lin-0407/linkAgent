package com.link.linkagent.creator.bilibili.model;

import java.time.LocalDateTime;

/**
 * B站账号绑定表（creator_bilibili_account）数据库记录。
 * 使用普通 JavaBean，让 MyBatis 可以通过无参构造和 setter 稳定回填查询结果，
 * 不依赖 Java record 的构造器参数名或框架版本兼容性。
 */
public class BilibiliAccountRecord {

    private Long id;
    private String accountId;
    private String userId;
    private String bilibiliUid;
    private String nickname;
    private String avatarUrl;
    private String bindStatus;
    private LocalDateTime lastSyncTime;
    private String lastSyncError;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    /** MyBatis 查询时先创建空对象，再逐字段调用 setter。 */
    public BilibiliAccountRecord() {
    }

    /** 业务创建账号绑定时使用全参构造，保持写入字段一次性完整初始化。 */
    public BilibiliAccountRecord(Long id, String accountId, String userId, String bilibiliUid,
                                 String nickname, String avatarUrl, String bindStatus,
                                 LocalDateTime lastSyncTime, String lastSyncError,
                                 LocalDateTime createTime, LocalDateTime updateTime) {
        this.id = id;
        this.accountId = accountId;
        this.userId = userId;
        this.bilibiliUid = bilibiliUid;
        this.nickname = nickname;
        this.avatarUrl = avatarUrl;
        this.bindStatus = bindStatus;
        this.lastSyncTime = lastSyncTime;
        this.lastSyncError = lastSyncError;
        this.createTime = createTime;
        this.updateTime = updateTime;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getBilibiliUid() { return bilibiliUid; }
    public void setBilibiliUid(String bilibiliUid) { this.bilibiliUid = bilibiliUid; }
    public String getNickname() { return nickname; }
    public void setNickname(String nickname) { this.nickname = nickname; }
    public String getAvatarUrl() { return avatarUrl; }
    public void setAvatarUrl(String avatarUrl) { this.avatarUrl = avatarUrl; }
    public String getBindStatus() { return bindStatus; }
    public void setBindStatus(String bindStatus) { this.bindStatus = bindStatus; }
    public LocalDateTime getLastSyncTime() { return lastSyncTime; }
    public void setLastSyncTime(LocalDateTime lastSyncTime) { this.lastSyncTime = lastSyncTime; }
    public String getLastSyncError() { return lastSyncError; }
    public void setLastSyncError(String lastSyncError) { this.lastSyncError = lastSyncError; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}
