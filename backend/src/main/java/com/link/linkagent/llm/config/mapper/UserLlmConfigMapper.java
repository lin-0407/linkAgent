package com.link.linkagent.llm.config.mapper;

import com.link.linkagent.llm.config.model.UserLlmConfigRecord;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Optional;

/**
 * 用户 LLM/Embedding 配置 MySQL 访问层（P1-4）。
 * <p>
 * 当前阶段使用注解 SQL：表结构简单（单表 CRUD），先降低 XML 配置成本。
 * 后续如果查询复杂度上升（如按 provider 聚合统计），再考虑迁移到 XML。
 */
@Mapper
public interface UserLlmConfigMapper {

    /**
     * 插入或更新用户配置（按 user_id + provider 唯一约束 upsert）。
     * <p>
     * 为什么用 ON DUPLICATE KEY UPDATE 而非先查再改？
     * 避免并发场景下的竞态条件——两个请求同时保存同 provider 配置时，
     * 先查后改可能造成数据覆盖或重复插入异常。
     */
    @Insert("""
            INSERT INTO user_llm_config (
                config_id, user_id, provider,
                llm_base_url, llm_api_key_enc, llm_model_name,
                embedding_base_url, embedding_api_key_enc, embedding_model_name,
                create_time, update_time
            )
            VALUES (
                #{configId}, #{userId}, #{provider},
                #{llmBaseUrl}, #{llmApiKeyEnc}, #{llmModelName},
                #{embeddingBaseUrl}, #{embeddingApiKeyEnc}, #{embeddingModelName},
                #{createTime}, #{updateTime}
            )
            ON DUPLICATE KEY UPDATE
                config_id = VALUES(config_id),
                llm_base_url = VALUES(llm_base_url),
                llm_api_key_enc = IF(VALUES(llm_api_key_enc) IS NULL, llm_api_key_enc, VALUES(llm_api_key_enc)),
                llm_model_name = VALUES(llm_model_name),
                embedding_base_url = VALUES(embedding_base_url),
                embedding_api_key_enc = IF(VALUES(embedding_api_key_enc) IS NULL, embedding_api_key_enc, VALUES(embedding_api_key_enc)),
                embedding_model_name = VALUES(embedding_model_name),
                update_time = VALUES(update_time),
                is_deleted = 0
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int upsert(UserLlmConfigRecord record);

    @Select("""
            SELECT id, config_id, user_id, provider,
                   llm_base_url, llm_api_key_enc, llm_model_name,
                   embedding_base_url, embedding_api_key_enc, embedding_model_name,
                   create_time, update_time, is_deleted
            FROM user_llm_config
            WHERE user_id = #{userId}
              AND is_deleted = 0
            ORDER BY provider ASC
            """)
    List<UserLlmConfigRecord> listByUser(@Param("userId") String userId);

    @Select("""
            SELECT id, config_id, user_id, provider,
                   llm_base_url, llm_api_key_enc, llm_model_name,
                   embedding_base_url, embedding_api_key_enc, embedding_model_name,
                   create_time, update_time, is_deleted
            FROM user_llm_config
            WHERE config_id = #{configId}
              AND is_deleted = 0
            LIMIT 1
            """)
    Optional<UserLlmConfigRecord> findByConfigId(@Param("configId") String configId);

    @Update("""
            UPDATE user_llm_config
            SET is_deleted = 1,
                update_time = CURRENT_TIMESTAMP
            WHERE config_id = #{configId}
              AND user_id = #{userId}
              AND is_deleted = 0
            """)
    int softDelete(@Param("configId") String configId, @Param("userId") String userId);
}
