package com.link.linkagent.creator.creation.execution;

import org.apache.ibatis.annotations.*;
import java.util.Optional;

@Mapper
public interface ProviderConfigurationMapper {
    @Select("SELECT id FROM creator_creation_provider_lock WHERE id=1 FOR UPDATE")
    int lockStarts();
    @Select("SELECT configuration_json FROM creator_creation_provider_configuration WHERE id=1 FOR UPDATE")
    Optional<String> lockSaved();
    @Select("SELECT configuration_json FROM creator_creation_provider_configuration WHERE id=1")
    Optional<String> saved();
    @Insert("INSERT INTO creator_creation_provider_configuration(id,configuration_json) VALUES(1,#{configuration}) ON DUPLICATE KEY UPDATE configuration_json=VALUES(configuration_json)")
    int save(@Param("configuration")String configuration);
    @Select("""
        SELECT (SELECT COUNT(*) FROM creator_creation_run WHERE status IN ('QUEUED','RUNNING','WAITING_DEPENDENCY','CANCEL_REQUESTED'))
        + (SELECT COUNT(*) FROM creator_creation_execution WHERE status IN ('ACTIVE','WAITING_USER','CANCEL_REQUESTED'))
        + (SELECT COUNT(*) FROM creator_preflight_review WHERE status IN ('QUEUED','RUNNING','RETRY_WAIT','CANCEL_REQUESTED') AND is_deleted=0)
        """)
    int activeCalls();
}
