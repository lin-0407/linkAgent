package com.link.linkagent.dto;

/**
 * 会话消息历史的一项。
 *
 * @param role       消息角色：user / assistant；summary 表示这是一条摘要检查点（历史在这里被压缩过）
 * @param content    消息内容；role=summary 时是摘要正文
 * @param tokenCount 摘要检查点本次压缩释放的 token 数；普通消息为 null
 */
public record SessionMessageItem(
        String role,
        String content,
        Integer tokenCount
) {
}
