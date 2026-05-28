-- ============================================================
-- link_agent 数据库初始化脚本
-- 执行方式：mysql -u root -p < init.sql
-- ============================================================

CREATE DATABASE IF NOT EXISTS link_agent DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE link_agent;

-- ------------------------------------------------------------
-- 1. 会话表
--    管理一次对话的生命周期（阶段 1 起用）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_conversation_session
(
    id          BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    session_id  VARCHAR(64)  NOT NULL COMMENT '会话唯一标识（UUID）',
    user_id     VARCHAR(64)  NOT NULL DEFAULT 'default' COMMENT '用户标识',
    title       VARCHAR(255) NOT NULL DEFAULT '' COMMENT '会话标题（首条消息自动截取）',
    status      TINYINT      NOT NULL DEFAULT 0 COMMENT '状态：0=进行中，1=已归档',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    is_deleted  TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0=正常，1=已删除',
    UNIQUE KEY uk_session_id (session_id),
    KEY idx_user_id (user_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = '会话表';

-- ------------------------------------------------------------
-- 2. 消息表
--    存储完整对话历史，是长期记忆的原始数据来源（阶段 1 起用）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_conversation_message
(
    id          BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    session_id  VARCHAR(64) NOT NULL COMMENT '关联 t_conversation_session.session_id',
    role        VARCHAR(16) NOT NULL COMMENT '角色：user / assistant / system / tool',
    content     LONGTEXT    NOT NULL COMMENT '消息内容',
    tool_name   VARCHAR(64)          DEFAULT NULL COMMENT '工具名称（role=tool 时有值）',
    token_count INT         NOT NULL DEFAULT 0 COMMENT '本条消息消耗的 token 数',
    create_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    is_deleted  TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    KEY idx_session_id (session_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = '消息表';

-- ------------------------------------------------------------
-- 3. 长期记忆表
--    存储 LLM 从对话中提炼出的用户事实 / 偏好（阶段 2 起用）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_long_term_memory
(
    id           BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    user_id      VARCHAR(64)  NOT NULL COMMENT '用户标识',
    memory_key   VARCHAR(128) NOT NULL COMMENT '记忆键（如 user.preference.language）',
    content      TEXT         NOT NULL COMMENT '记忆内容',
    source_session_id VARCHAR(64)   DEFAULT NULL COMMENT '来源会话',
    embedding_id VARCHAR(128)       DEFAULT NULL COMMENT 'Milvus 向量 ID，用于相似度检索',
    create_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    is_deleted   TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    KEY idx_user_id (user_id),
    UNIQUE KEY uk_user_memory_key (user_id, memory_key)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = '长期记忆表';

-- ------------------------------------------------------------
-- 4. Agent 执行链路表
--    记录一次 Agent 调用的整体状态，是可观测性的顶层节点（阶段 6 起用，阶段 1 可提前插数据）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_agent_trace
(
    id           BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    trace_id     VARCHAR(64)  NOT NULL COMMENT '链路唯一标识（UUID）',
    session_id   VARCHAR(64)  NOT NULL COMMENT '关联会话',
    user_input   TEXT         NOT NULL COMMENT '用户原始输入',
    final_output LONGTEXT              DEFAULT NULL COMMENT 'Agent 最终输出',
    status       TINYINT      NOT NULL DEFAULT 0 COMMENT '状态：0=运行中，1=成功，2=失败',
    total_tokens INT          NOT NULL DEFAULT 0 COMMENT '本次调用总 token 消耗',
    total_steps  INT          NOT NULL DEFAULT 0 COMMENT '迭代步数',
    start_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '开始时间',
    end_time     DATETIME              DEFAULT NULL COMMENT '结束时间',
    error_msg    VARCHAR(512)          DEFAULT NULL COMMENT '失败原因',
    create_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    is_deleted   TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    UNIQUE KEY uk_trace_id (trace_id),
    KEY idx_session_id (session_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = 'Agent 执行链路表';

-- ------------------------------------------------------------
-- 5. Agent 执行步骤表
--    记录每一次 Thought / Action / Observation，是 ReAct 流程的完整快照（阶段 6 起用）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_agent_step
(
    id          BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    trace_id    VARCHAR(64) NOT NULL COMMENT '关联 t_agent_trace.trace_id',
    step_index  INT         NOT NULL COMMENT '步骤序号，从 0 开始',
    step_type   VARCHAR(16) NOT NULL COMMENT '步骤类型：thought / action / observation / final',
    content     LONGTEXT    NOT NULL COMMENT '步骤内容',
    tool_name   VARCHAR(64)          DEFAULT NULL COMMENT '调用的工具名（step_type=action 时有值）',
    tool_input  TEXT                 DEFAULT NULL COMMENT '工具入参 JSON',
    tool_output TEXT                 DEFAULT NULL COMMENT '工具返回 JSON',
    token_count INT         NOT NULL DEFAULT 0 COMMENT '本步 token 消耗',
    create_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    is_deleted  TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    KEY idx_trace_id (trace_id),
    KEY idx_trace_step (trace_id, step_index)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = 'Agent 执行步骤表';

-- ------------------------------------------------------------
-- 6. 创作任务表
--    作为 UP 主智能工作台的业务主表，后续发布前分析、反馈分析、复盘报告都挂在任务下
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS creator_task
(
    id          BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    task_id     VARCHAR(64)  NOT NULL COMMENT '创作任务唯一标识（UUID）',
    user_id     VARCHAR(64)  NOT NULL DEFAULT 'default' COMMENT '用户标识，第一版允许默认用户方便本地演示',
    task_name   VARCHAR(128) NOT NULL COMMENT '任务名称，用于列表页快速识别本次创作',
    status      VARCHAR(32)  NOT NULL DEFAULT 'DRAFT' COMMENT '任务状态：DRAFT=草稿，PRE_PUBLISH_ANALYZED=已完成发布前分析，FEEDBACK_ANALYZED=已完成反馈分析，COMPETITOR_ANALYZED=已完成竞品分析，ANALYZED=已完成复盘，ARCHIVED=已归档',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    is_deleted  TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0=正常，1=已删除',
    UNIQUE KEY uk_task_id (task_id),
    KEY idx_user_id (user_id),
    KEY idx_user_update_time (user_id, update_time)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = '创作任务表';

-- ------------------------------------------------------------
-- 7. 创作材料表
--    存储用户主动输入的字幕、文稿、标题草稿和简介草稿，第一版不做平台爬取
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS creator_material
(
    id            BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    task_id       VARCHAR(64) NOT NULL COMMENT '关联 creator_task.task_id',
    material_type VARCHAR(32) NOT NULL COMMENT '材料类型：TITLE_DRAFT / DESCRIPTION_DRAFT / MANUSCRIPT / SUBTITLE',
    content       LONGTEXT    NOT NULL COMMENT '用户主动输入的材料内容',
    create_time   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    is_deleted    TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除：0=正常，1=已删除',
    UNIQUE KEY uk_task_material_type (task_id, material_type),
    KEY idx_task_id (task_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = '创作材料表';

-- ------------------------------------------------------------
-- 8. 发布前优化建议表
--    保存 LLM 基于创作材料生成的标题、简介、标签和风险建议，便于后续复盘与评测
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS creator_suggestion
(
    id                     BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    suggestion_id          VARCHAR(64)  NOT NULL COMMENT '建议唯一标识（UUID）',
    task_id                VARCHAR(64)  NOT NULL COMMENT '关联 creator_task.task_id',
    content_summary        TEXT                  DEFAULT NULL COMMENT '内容摘要',
    audience_profile       TEXT                  DEFAULT NULL COMMENT '目标受众判断',
    selling_points         TEXT                  DEFAULT NULL COMMENT '核心卖点列表 JSON',
    risk_points            TEXT                  DEFAULT NULL COMMENT '风险点列表 JSON',
    title_suggestions      TEXT                  DEFAULT NULL COMMENT '标题建议列表 JSON',
    description_suggestion TEXT                  DEFAULT NULL COMMENT '简介建议',
    tag_suggestions        TEXT                  DEFAULT NULL COMMENT '标签建议列表 JSON',
    partition_suggestion   VARCHAR(128)          DEFAULT NULL COMMENT '分区建议',
    raw_output             LONGTEXT     NOT NULL COMMENT 'LLM 原始输出，用于失败回放和人工检查',
    parse_status           VARCHAR(32)  NOT NULL DEFAULT 'PARSED' COMMENT '解析状态：PARSED=已解析，RAW_ONLY=仅保存原文',
    create_time            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    is_deleted             TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0=正常，1=已删除',
    UNIQUE KEY uk_suggestion_id (suggestion_id),
    UNIQUE KEY uk_task_id (task_id),
    KEY idx_task_update_time (task_id, update_time)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = '发布前优化建议表';

-- ------------------------------------------------------------
-- 9. 评论弹幕样例表
--    保存用户主动粘贴的评论和弹幕样例，第一版不做平台爬取
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS creator_user_feedback_detail
(
    id              BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    feedback_id     VARCHAR(64) NOT NULL COMMENT '反馈样例唯一标识（UUID）',
    task_id         VARCHAR(64) NOT NULL COMMENT '关联 creator_task.task_id',
    comment_samples LONGTEXT             DEFAULT NULL COMMENT '用户主动粘贴的评论样例',
    danmaku_samples LONGTEXT             DEFAULT NULL COMMENT '用户主动粘贴的弹幕样例',
    extra_context   VARCHAR(500)         DEFAULT NULL COMMENT '用户补充的反馈背景，例如发布时间或视频表现',
    create_time     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    is_deleted      TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除：0=正常，1=已删除',
    UNIQUE KEY uk_feedback_id (feedback_id),
    UNIQUE KEY uk_task_id (task_id),
    KEY idx_task_update_time (task_id, update_time)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = '评论弹幕样例表';

-- ------------------------------------------------------------
-- 10. 评论弹幕分析报告表
--     保存 LLM 对评论弹幕样例的结构化分析结果，供后续复盘报告汇总
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS creator_llm_feedback_report
(
    id                       BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    report_id                VARCHAR(64) NOT NULL COMMENT '反馈分析报告唯一标识（UUID）',
    task_id                  VARCHAR(64) NOT NULL COMMENT '关联 creator_task.task_id',
    feedback_summary         TEXT                 DEFAULT NULL COMMENT '观众整体反馈摘要',
    hot_topics               TEXT                 DEFAULT NULL COMMENT '高频观点列表 JSON',
    sentiment_summary        TEXT                 DEFAULT NULL COMMENT '情绪倾向总结',
    controversy_points       TEXT                 DEFAULT NULL COMMENT '争议点列表 JSON',
    misunderstanding_points  TEXT                 DEFAULT NULL COMMENT '误解点列表 JSON',
    next_content_suggestions TEXT                 DEFAULT NULL COMMENT '下一期内容建议列表 JSON',
    interaction_suggestions  TEXT                 DEFAULT NULL COMMENT '互动回应建议列表 JSON',
    raw_output               LONGTEXT    NOT NULL COMMENT 'LLM 原始输出，用于失败回放和人工检查',
    parse_status             VARCHAR(32) NOT NULL DEFAULT 'PARSED' COMMENT '解析状态：PARSED=已解析，RAW_ONLY=仅保存原文',
    create_time              DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time              DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    is_deleted               TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除：0=正常，1=已删除',
    UNIQUE KEY uk_report_id (report_id),
    UNIQUE KEY uk_task_id (task_id),
    KEY idx_task_update_time (task_id, update_time)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = '评论弹幕分析报告表';

-- ------------------------------------------------------------
-- 11. 同类型视频竞品样例表
--     保存用户主动整理的同类视频信息，第一版不做平台抓取
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS creator_competitor_sample
(
    id                 BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    competitor_bv_id   VARCHAR(12)  NOT NULL COMMENT '竞品视频 BV 号',
    competitor_video_name VARCHAR(200) NOT NULL COMMENT '竞品视频名称',
    task_id            VARCHAR(64) NOT NULL COMMENT '关联 creator_task.task_id',
    category           VARCHAR(128)         DEFAULT NULL COMMENT '同类型视频分类，例如 AI 工具教程、剪辑技巧、游戏实况',
    competitor_samples LONGTEXT    NOT NULL COMMENT '用户主动整理的竞品分析文本',
    compare_dimension  VARCHAR(500)         DEFAULT NULL COMMENT '用户希望重点对比的维度',
    extra_context      VARCHAR(500)         DEFAULT NULL COMMENT '补充背景，例如样例来源或选择原因',
    create_time        DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time        DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    is_deleted         TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除：0=正常，1=已删除',
    UNIQUE KEY uk_task_id (task_id),
    KEY idx_competitor_bv_id (competitor_bv_id),
    KEY idx_task_update_time (task_id, update_time)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = '同类型视频竞品样例表';

-- 已建过旧版 creator_competitor_sample 的本地库需要补齐 BV 号和视频名称字段，并把旧 sample_id 改成可空，避免新代码插入时报错。
SET @add_competitor_bv_sql = (
    SELECT IF(COUNT(*) = 0,
              'ALTER TABLE creator_competitor_sample ADD COLUMN competitor_bv_id VARCHAR(12) DEFAULT NULL COMMENT ''竞品视频 BV 号'' AFTER id',
              'SELECT 1')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'creator_competitor_sample'
      AND COLUMN_NAME = 'competitor_bv_id'
);
PREPARE add_competitor_bv_stmt FROM @add_competitor_bv_sql;
EXECUTE add_competitor_bv_stmt;
DEALLOCATE PREPARE add_competitor_bv_stmt;

SET @add_competitor_name_sql = (
    SELECT IF(COUNT(*) = 0,
              'ALTER TABLE creator_competitor_sample ADD COLUMN competitor_video_name VARCHAR(200) DEFAULT NULL COMMENT ''竞品视频名称'' AFTER competitor_bv_id',
              'SELECT 1')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'creator_competitor_sample'
      AND COLUMN_NAME = 'competitor_video_name'
);
PREPARE add_competitor_name_stmt FROM @add_competitor_name_sql;
EXECUTE add_competitor_name_stmt;
DEALLOCATE PREPARE add_competitor_name_stmt;

SET @compat_sample_id_sql = (
    SELECT IF(COUNT(*) > 0,
              'ALTER TABLE creator_competitor_sample MODIFY COLUMN sample_id VARCHAR(64) NULL DEFAULT NULL COMMENT ''竞品样例兼容字段（旧 UUID，已停用）''',
              'SELECT 1')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'creator_competitor_sample'
      AND COLUMN_NAME = 'sample_id'
);
PREPARE compat_sample_id_stmt FROM @compat_sample_id_sql;
EXECUTE compat_sample_id_stmt;
DEALLOCATE PREPARE compat_sample_id_stmt;

-- ------------------------------------------------------------
-- 12. 同类型视频竞品分析报告表
--     基于用户主动提供的同类视频样例，保存本视频相对竞品的优劣势分析
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS creator_competitor_report
(
    id                       BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    report_id                VARCHAR(64) NOT NULL COMMENT '竞品分析报告唯一标识（UUID）',
    task_id                  VARCHAR(64) NOT NULL COMMENT '关联 creator_task.task_id',
    competitor_summary       TEXT                 DEFAULT NULL COMMENT '同类型视频整体打法总结',
    competitor_advantages    TEXT                 DEFAULT NULL COMMENT '竞品优势列表 JSON',
    own_advantages           TEXT                 DEFAULT NULL COMMENT '本视频相对优势 JSON',
    own_disadvantages        TEXT                 DEFAULT NULL COMMENT '本视频相对短板 JSON',
    gap_analysis             TEXT                 DEFAULT NULL COMMENT '差距分析 JSON',
    improvement_suggestions  TEXT                 DEFAULT NULL COMMENT '改进建议 JSON',
    differentiation_strategy TEXT                 DEFAULT NULL COMMENT '差异化定位建议',
    raw_output               LONGTEXT    NOT NULL COMMENT 'LLM 原始输出，用于失败回放和人工检查',
    parse_status             VARCHAR(32) NOT NULL DEFAULT 'PARSED' COMMENT '解析状态：PARSED=已解析，RAW_ONLY=仅保存原文',
    create_time              DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time              DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    is_deleted               TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除：0=正常，1=已删除',
    UNIQUE KEY uk_report_id (report_id),
    UNIQUE KEY uk_task_id (task_id),
    KEY idx_task_update_time (task_id, update_time)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = '同类型视频竞品分析报告表';

-- ------------------------------------------------------------
-- 13. 创作复盘报告表
--     汇总发布前优化建议和评论弹幕分析结果，形成任务级的最终复盘产物
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS creator_report
(
    id                         BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    report_id                  VARCHAR(64)  NOT NULL COMMENT '复盘报告唯一标识（UUID）',
    task_id                    VARCHAR(64)  NOT NULL COMMENT '关联 creator_task.task_id',
    content_summary            TEXT                  DEFAULT NULL COMMENT '内容摘要',
    core_selling_points        TEXT                  DEFAULT NULL COMMENT '核心卖点列表 JSON',
    title_description_review   TEXT                  DEFAULT NULL COMMENT '标题、简介、标签和分区复盘 JSON',
    audience_feedback_summary  TEXT                  DEFAULT NULL COMMENT '观众反馈摘要',
    competitor_comparison      TEXT                  DEFAULT NULL COMMENT '竞品对照结论 JSON',
    controversy_and_misunderstanding TEXT             DEFAULT NULL COMMENT '争议和误解点 JSON',
    next_action_suggestions    TEXT                  DEFAULT NULL COMMENT '下一步动作建议 JSON',
    creator_preference_insight TEXT                   DEFAULT NULL COMMENT '创作者偏好洞察 JSON',
    overall_conclusion         TEXT                  DEFAULT NULL COMMENT '复盘总判断',
    raw_output                 LONGTEXT     NOT NULL COMMENT 'LLM 原始输出，用于失败回放和人工检查',
    parse_status               VARCHAR(32)  NOT NULL DEFAULT 'PARSED' COMMENT '解析状态：PARSED=已解析，RAW_ONLY=仅保存原文',
    create_time                DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time                DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    is_deleted                 TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0=正常，1=已删除',
    UNIQUE KEY uk_report_id (report_id),
    UNIQUE KEY uk_task_id (task_id),
    KEY idx_task_update_time (task_id, update_time)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = '创作复盘报告表';

-- 已建过旧版 creator_report 的本地库需要补齐竞品对照字段，使用 information_schema 避免重复执行时报错。
SET @add_competitor_comparison_sql = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE creator_report ADD COLUMN competitor_comparison TEXT DEFAULT NULL COMMENT ''竞品对照结论 JSON'' AFTER audience_feedback_summary',
        'SELECT 1'
    )
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'creator_report'
      AND COLUMN_NAME = 'competitor_comparison'
);
PREPARE add_competitor_comparison_stmt FROM @add_competitor_comparison_sql;
EXECUTE add_competitor_comparison_stmt;
DEALLOCATE PREPARE add_competitor_comparison_stmt;
