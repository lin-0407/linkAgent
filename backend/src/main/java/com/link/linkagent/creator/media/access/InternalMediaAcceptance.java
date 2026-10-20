package com.link.linkagent.creator.media.access;

/**
 * 内部受理凭据：只有整片运行自己的执行者能创建媒体子任务。
 * <p>
 * 人工入口继续使用原有的“有活动制作运行即拒绝”规则；本接口只放宽内部调用，
 * 并要求调用方在创建子任务前核对运行、精确版本、授权和执行租约，
 * 因此不是全局放宽冲突门禁。
 */
public interface InternalMediaAcceptance {

    /**
     * 校验本次受理确实属于当前运行和精确版本。
     * 不满足时抛出冲突，子任务不会被创建，也不会产生新的付费调用。
     */
    void verify(String taskId, String ownerId, String versionId);
}
