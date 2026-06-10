package com.link.linkagent.prompt.model;

import java.time.LocalDateTime;

/**
 * 提示词模板实体，对应 llm_prompt_template 表的一行。
 * 把原本写死在各 Service 里的大模型提示词搬到数据库后，调用方按 promptKey 取词，
 * 改一句提示词不必再改代码、重新打包发版，配合 PromptService 的缓存刷新即可运行期热更新。
 * 各字段含义以建表语句里的列 COMMENT 为准，这里不重复注释，避免两处说明漂移。
 */
public class PromptTemplate {

    private Long id;
    private String promptKey;
    private String promptType;
    private String scene;
    private String content;
    private String description;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getPromptKey() {
        return promptKey;
    }

    public void setPromptKey(String promptKey) {
        this.promptKey = promptKey;
    }

    public String getPromptType() {
        return promptType;
    }

    public void setPromptType(String promptType) {
        this.promptType = promptType;
    }

    public String getScene() {
        return scene;
    }

    public void setScene(String scene) {
        this.scene = scene;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    public LocalDateTime getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(LocalDateTime updateTime) {
        this.updateTime = updateTime;
    }
}
