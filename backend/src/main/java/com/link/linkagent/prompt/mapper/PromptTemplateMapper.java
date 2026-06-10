package com.link.linkagent.prompt.mapper;

import com.link.linkagent.prompt.model.PromptTemplate;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Optional;

/**
 * 提示词模板数据访问层。
 * 沿用项目统一的 MyBatis 注解式写法（无 XML 文件）。
 * 读：列出全部 + 按 key 查一条（5.5-1）；写：按 key 改正文用于热更新（5.5-2）。
 */
@Mapper
public interface PromptTemplateMapper {

    /**
     * 列出全部未删除提示词，按场景再按 key 排序，方便前端按场景分组稳定展示。
     */
    @Select("""
            SELECT id,
                   prompt_key,
                   prompt_type,
                   scene,
                   content,
                   description,
                   create_time,
                   update_time
            FROM llm_prompt_template
            WHERE is_deleted = 0
            ORDER BY scene, prompt_key
            """)
    @Results(id = "PromptTemplateMap", value = {
            @Result(column = "id", property = "id"),
            @Result(column = "prompt_key", property = "promptKey"),
            @Result(column = "prompt_type", property = "promptType"),
            @Result(column = "scene", property = "scene"),
            @Result(column = "content", property = "content"),
            @Result(column = "description", property = "description"),
            @Result(column = "create_time", property = "createTime"),
            @Result(column = "update_time", property = "updateTime")
    })
    List<PromptTemplate> listAll();

    /**
     * 按唯一键查一条提示词。返回 Optional 让上层显式处理「查不到」，而不是默默拿到 null。
     */
    @Select("""
            SELECT id,
                   prompt_key,
                   prompt_type,
                   scene,
                   content,
                   description,
                   create_time,
                   update_time
            FROM llm_prompt_template
            WHERE prompt_key = #{key}
              AND is_deleted = 0
            LIMIT 1
            """)
    @ResultMap("PromptTemplateMap")
    Optional<PromptTemplate> findByKey(@Param("key") String key);

    /**
     * 按 key 改写提示词正文（5.5-2 热更新用）。
     * 只更新 content：update_time 靠建表时的 ON UPDATE CURRENT_TIMESTAMP 自动刷新，不在这里手动赋值。
     * 返回受影响行数，上层据此判断 key 是否存在——0 行说明这条 key 不存在或已被逻辑删除。
     */
    @Update("""
            UPDATE llm_prompt_template
            SET content = #{content}
            WHERE prompt_key = #{key}
              AND is_deleted = 0
            """)
    int updateContentByKey(@Param("key") String key, @Param("content") String content);
}
