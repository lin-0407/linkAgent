package com.link.linkagent.creator.creation.revision;

import com.link.linkagent.creator.creation.revision.RevisionModels.Proposal;
import org.apache.ibatis.annotations.*;
import java.util.Optional;

@Mapper
public interface RevisionMapper {
    String COLUMNS="proposal_id,task_id,owner_id,storyboard_id,expected_workspace_revision,idempotency_key,request_hash,document_json,applied_storyboard_id";
    @Select("SELECT "+COLUMNS+" FROM creator_creation_revision_proposal WHERE task_id=#{task} AND owner_id=#{owner} AND proposal_id=#{id}")
    Optional<Proposal> find(@Param("task")String task,@Param("owner")String owner,@Param("id")String id);
    @Select("SELECT "+COLUMNS+" FROM creator_creation_revision_proposal WHERE task_id=#{task} AND owner_id=#{owner} AND idempotency_key=#{key}")
    Optional<Proposal> byKey(@Param("task")String task,@Param("owner")String owner,@Param("key")String key);
    @Insert("""
        INSERT INTO creator_creation_revision_proposal(proposal_id,task_id,owner_id,storyboard_id,expected_workspace_revision,idempotency_key,request_hash,document_json)
        VALUES(#{proposalId},#{taskId},#{ownerId},#{storyboardId},#{expectedWorkspaceRevision},#{idempotencyKey},#{requestHash},#{documentJson})
        """)
    int insert(Proposal proposal);
    @Update("UPDATE creator_creation_revision_proposal SET applied_storyboard_id=#{board} WHERE proposal_id=#{id} AND applied_storyboard_id IS NULL")
    int apply(@Param("id")String id,@Param("board")String board);
}
