package com.link.linkagent.creator.report.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 创作复盘链路失效访问层。
 * 反馈和竞品发生变化时统一在这里清理下游产物，避免各业务服务各自维护跨表 SQL。
 */
@Mapper
public interface CreatorReviewInvalidationMapper {

    @Update("""
            UPDATE creator_llm_feedback_report
            SET is_deleted = 1,
                update_time = CURRENT_TIMESTAMP
            WHERE task_id = #{taskId}
              AND is_deleted = 0
            """)
    int invalidateFeedbackReport(@Param("taskId") String taskId);

    @Update("""
            UPDATE creator_competitor_report
            SET is_deleted = 1,
                update_time = CURRENT_TIMESTAMP
            WHERE task_id = #{taskId}
              AND is_deleted = 0
            """)
    int invalidateCompetitorReport(@Param("taskId") String taskId);

    @Update("""
            UPDATE creator_report
            SET is_deleted = 1,
                update_time = CURRENT_TIMESTAMP
            WHERE task_id = #{taskId}
              AND is_deleted = 0
            """)
    int invalidateCreatorReport(@Param("taskId") String taskId);

    /**
     * 只失效总体复盘生成的偏好，用户主动采用或拒绝建议形成的记录必须继续保留。
     */
    @Update("""
            UPDATE creator_preference
            SET is_deleted = 1,
                update_time = CURRENT_TIMESTAMP
            WHERE source_task_id = #{taskId}
              AND source_report_id NOT LIKE 'ADOPTION_FEEDBACK_%'
              AND is_deleted = 0
            """)
    int invalidateGeneratedPreference(@Param("taskId") String taskId);

}
