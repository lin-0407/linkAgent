package com.link.linkagent.creator.creation.execution;

import org.apache.ibatis.annotations.*;
import java.util.List;
import java.util.Optional;

@Mapper
public interface ManualReviewMapper {
    record Entry(String reviewId,String runId,String taskId,String ownerId,String versionId,String status) { }
    @Select("SELECT review_id,run_id,task_id,owner_id,version_id,status FROM creator_creation_manual_review WHERE review_id=#{id} ORDER BY create_time DESC")
    List<Entry> byReview(@Param("id")String id);
    @Select("""
        SELECT m.review_id,m.run_id,m.task_id,m.owner_id,m.version_id,m.status FROM creator_creation_manual_review m
        JOIN creator_creation_cost_entry c ON c.run_id=m.run_id AND c.task_id=m.task_id AND c.owner_id=m.owner_id
        WHERE m.review_id=#{review} AND c.charge_key=#{key} LIMIT 1
        """)
    Optional<Entry> byCharge(@Param("review")String review,@Param("key")String key);
    @Select("SELECT review_id,run_id,task_id,owner_id,version_id,status FROM creator_creation_manual_review WHERE status='ACTIVE' LIMIT 20")
    List<Entry> candidates();
    @Insert("INSERT INTO creator_creation_manual_review(review_id,run_id,task_id,owner_id,version_id) VALUES(#{reviewId},#{runId},#{taskId},#{ownerId},#{versionId})")
    int insert(Entry entry);
    @Select("SELECT status FROM creator_preflight_review WHERE review_id=#{id} AND task_id=#{task} AND owner_id=#{owner}")
    Optional<String> reviewStatus(@Param("id")String id,@Param("task")String task,@Param("owner")String owner);
    @Update("UPDATE creator_creation_manual_review SET status=#{status} WHERE review_id=#{reviewId} AND run_id=#{runId} AND status='ACTIVE'")
    int finish(Entry entry);
}
