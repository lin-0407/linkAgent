package com.link.linkagent.settings.mapper;

import com.link.linkagent.settings.model.RuntimeSettingRecord;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Optional;

/**
 * 运行期设置数据访问层。
 * 只允许按服务端白名单 key 读写；白名单校验放在 Service，Mapper 保持单纯数据访问职责。
 */
@Mapper
public interface RuntimeSettingMapper {

    @Select("""
            SELECT id,
                   setting_key,
                   setting_value,
                   description,
                   create_time,
                   update_time
            FROM app_runtime_setting
            WHERE setting_key = #{settingKey}
              AND is_deleted = 0
            LIMIT 1
            """)
    @Results(id = "RuntimeSettingMap", value = {
            @Result(column = "id", property = "id"),
            @Result(column = "setting_key", property = "settingKey"),
            @Result(column = "setting_value", property = "settingValue"),
            @Result(column = "description", property = "description"),
            @Result(column = "create_time", property = "createTime"),
            @Result(column = "update_time", property = "updateTime")
    })
    Optional<RuntimeSettingRecord> findByKey(@Param("settingKey") String settingKey);

    @Select("""
            SELECT id,
                   setting_key,
                   setting_value,
                   description,
                   create_time,
                   update_time
            FROM app_runtime_setting
            WHERE is_deleted = 0
            ORDER BY setting_key
            """)
    @ResultMap("RuntimeSettingMap")
    List<RuntimeSettingRecord> listAll();

    /**
     * 写入或更新运行期开关。
     * 用 MySQL upsert 是为了让首次修改某个开关时自动创建覆盖值，而不是要求人工先插一行。
     */
    @Insert("""
            INSERT INTO app_runtime_setting (setting_key, setting_value, description)
            VALUES (#{settingKey}, #{settingValue}, #{description})
            ON DUPLICATE KEY UPDATE
                setting_value = VALUES(setting_value),
                description = VALUES(description),
                is_deleted = 0
            """)
    int upsert(@Param("settingKey") String settingKey,
               @Param("settingValue") String settingValue,
               @Param("description") String description);
}
