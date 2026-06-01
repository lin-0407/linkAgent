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
-- 10.1 评论弹幕明细表
--      保存用户主动导入的单条评论/弹幕，支撑分类筛选、图表统计和后续证据追问
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS creator_feedback_item
(
    id              BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    item_id         VARCHAR(64)  NOT NULL COMMENT '明细唯一标识（UUID）',
    task_id         VARCHAR(64)  NOT NULL COMMENT '关联 creator_task.task_id',
    source_type     VARCHAR(16)  NOT NULL COMMENT '来源类型：COMMENT=评论，DANMAKU=弹幕',
    source_id       VARCHAR(64)           DEFAULT NULL COMMENT '平台侧原始 ID；用户粘贴或 TXT 导入没有原始 ID 时为空',
    content         LONGTEXT     NOT NULL COMMENT '评论或弹幕原文，来自用户粘贴、上传文件或页面 BV 采集脚本输出的样例',
    occur_time_text VARCHAR(64)           DEFAULT NULL COMMENT '弹幕出现时间或评论发布时间文本，用于时间段分析和证据展示',
    like_count      BIGINT UNSIGNED       DEFAULT NULL COMMENT '评论点赞量，点赞越多通常代表更多观众共鸣；弹幕或缺失数据为空',
    reply_count     INT UNSIGNED          DEFAULT NULL COMMENT '评论回复量，回复越多通常代表更多互动或争议；弹幕或缺失数据为空',
    category        VARCHAR(32)  NOT NULL DEFAULT 'OTHER' COMMENT '分类：APPROVAL/QUESTION/DOUBT/SUGGESTION/EMOTION/INTERACTION/KNOWLEDGE_REACTION/QUESTION_POINT/EMOTION_PEAK/RESONANCE/COMPLAINT/EMPTY_MEANING/DUPLICATE/OTHER',
    sentiment       VARCHAR(16)  NOT NULL DEFAULT 'NEUTRAL' COMMENT '情绪倾向：POSITIVE=正向，NEGATIVE=负向，NEUTRAL=中性',
    is_noise        TINYINT      NOT NULL DEFAULT 0 COMMENT '是否无意义内容：0=有效，1=无意义或重复内容',
    reason          VARCHAR(500)          DEFAULT NULL COMMENT '分类原因，说明当前规则为什么给出这个分类',
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    is_deleted      TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0=正常，1=已删除',
    UNIQUE KEY uk_item_id (item_id),
    KEY idx_task_source_category (task_id, source_type, category),
    KEY idx_task_source_like (task_id, source_type, like_count),
    KEY idx_task_sentiment (task_id, sentiment),
    KEY idx_task_create_time (task_id, create_time)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = '评论弹幕明细表';
------------------------------------------
-- 10.2 评论弹幕导入指标表
--      保存导入样例携带的视频基础指标，用于评论弹幕复盘时理解反馈规模
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS creator_feedback_metric
(
    id             BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    metric_id      VARCHAR(64) NOT NULL COMMENT '指标记录唯一标识（UUID）',
    task_id        VARCHAR(64) NOT NULL COMMENT '关联 creator_task.task_id',
    view_count     BIGINT UNSIGNED       DEFAULT NULL COMMENT '播放量，来自上传文件或页面 BV 采集脚本输出',
    favorite_count BIGINT UNSIGNED       DEFAULT NULL COMMENT '收藏量，来自上传文件或页面 BV 采集脚本输出',
    coin_count     BIGINT UNSIGNED       DEFAULT NULL COMMENT '投币量，来自上传文件或页面 BV 采集脚本输出',
    like_count     BIGINT UNSIGNED       DEFAULT NULL COMMENT '点赞量，来自上传文件或页面 BV 采集脚本输出',
    share_count    BIGINT UNSIGNED       DEFAULT NULL COMMENT '分享量，来自上传文件或页面 BV 采集脚本输出',
    source         VARCHAR(64)           DEFAULT NULL COMMENT '数据来源，例如 bilibili_public_web 或 uploaded_json',
    create_time    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    is_deleted     TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除：0=正常，1=已删除',
    UNIQUE KEY uk_metric_id (metric_id),
    UNIQUE KEY uk_task_id (task_id),
    KEY idx_task_create_time (task_id, create_time)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = '评论弹幕导入指标表';

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

-- ------------------------------------------------------------
-- 13.1 创作者长期偏好表
--      保存每期复盘提炼出的创作偏好快照，让下一期发布前优化能够读取历史经验
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS creator_preference
(
    id                 BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    preference_id      VARCHAR(64) NOT NULL COMMENT '创作者偏好快照唯一标识（UUID）',
    user_id            VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT '用户标识，用于隔离不同创作者的长期偏好',
    source_task_id     VARCHAR(64) NOT NULL COMMENT '来源创作任务 ID，用于追溯偏好来自哪一期复盘',
    source_report_id   VARCHAR(64) NOT NULL COMMENT '来源复盘报告 ID，用于报告重新生成后更新偏好内容',
    preference_content TEXT        NOT NULL COMMENT '创作者偏好洞察 JSON，由复盘报告提炼并在下一期发布前优化中作为参考',
    create_time        DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time        DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    is_deleted         TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除：0=正常，1=已删除',
    UNIQUE KEY uk_preference_id (preference_id),
    UNIQUE KEY uk_user_source_task (user_id, source_task_id),
    KEY idx_user_update_time (user_id, update_time)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = '创作者长期偏好表';

SET @add_competitor_comparison_sql = (
    SELECT IF(COUNT(*) = 0,
              'ALTER TABLE creator_report ADD COLUMN competitor_comparison TEXT DEFAULT NULL COMMENT ''竞品对照结论 JSON'' AFTER audience_feedback_summary',
              'SELECT 1')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'creator_report'
      AND COLUMN_NAME = 'competitor_comparison'
);
PREPARE add_competitor_comparison_stmt FROM @add_competitor_comparison_sql;
EXECUTE add_competitor_comparison_stmt;
DEALLOCATE PREPARE add_competitor_comparison_stmt;

-- ------------------------------------------------------------
-- 14. 创作工作流会话表
--     保存任务在某个业务阶段的一次会话，后续消息流、SSE 和确认状态都挂在这里
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS creator_workflow_session
(
    id                   BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    session_id           VARCHAR(64)  NOT NULL COMMENT '工作流会话唯一标识（UUID）',
    task_id              VARCHAR(64)  NOT NULL COMMENT '关联 creator_task.task_id',
    stage                VARCHAR(32)  NOT NULL COMMENT '业务阶段：PRE_PUBLISH=发布前优化，FEEDBACK=评论弹幕分析，REPORT=创作复盘报告',
    status               VARCHAR(32)  NOT NULL DEFAULT 'CREATED' COMMENT '会话状态：CREATED=已创建，CONTEXT_LOADING=正在装载任务材料，WAITING_USER_INPUT=等待用户补充输入，RUNNING=Agent正在分析，WAITING_CONFIRMATION=等待用户确认结果，CONFIRMED=用户已确认，FAILED=执行失败，CANCELLED=用户已取消',
    user_id              VARCHAR(64)  NOT NULL DEFAULT 'default' COMMENT '用户标识',
    confirmed_result_id  VARCHAR(64)           DEFAULT NULL COMMENT '用户确认后的结果 ID，用于记录采用的发布前建议',
    error_message        VARCHAR(500)          DEFAULT NULL COMMENT '失败原因',
    create_time          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    is_deleted           TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0=正常，1=已删除',
    UNIQUE KEY uk_session_id (session_id),
    KEY idx_task_stage_update_time (task_id, stage, update_time),
    KEY idx_task_id (task_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = '创作工作流会话表';

-- ------------------------------------------------------------
-- 15. 创作工作流消息表
--     保存会话内的过程消息和用户补充输入，保证页面刷新后可以从历史消息恢复
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS creator_workflow_message
(
    id               BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    message_id       VARCHAR(64)  NOT NULL COMMENT '消息唯一标识（UUID）',
    session_id       VARCHAR(64)  NOT NULL COMMENT '关联 creator_workflow_session.session_id',
    role             VARCHAR(16)  NOT NULL COMMENT '消息角色：SYSTEM=系统过程消息，USER=用户输入，AGENT=Agent分析消息，TOOL=工具执行结果，RESULT=结构化结果消息',
    content          LONGTEXT     NOT NULL COMMENT '消息正文',
    content_type     VARCHAR(32)  NOT NULL DEFAULT 'TEXT' COMMENT '内容类型：TEXT=普通文本，MATERIAL_SUMMARY=材料摘要，RESULT_CARD=结果卡片，ERROR=错误消息',
    detail_ref_type  VARCHAR(32)           DEFAULT NULL COMMENT '详情引用类型：MATERIAL=创作材料，后续可扩展 ATTACHMENT=附件、SUGGESTION=发布建议',
    detail_ref_id    VARCHAR(64)           DEFAULT NULL COMMENT '详情引用 ID',
    sequence_no      INT          NOT NULL COMMENT '会话内顺序号，从 1 开始',
    create_time      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    is_deleted       TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0=正常，1=已删除',
    UNIQUE KEY uk_message_id (message_id),
    UNIQUE KEY uk_session_sequence (session_id, sequence_no),
    KEY idx_session_id (session_id),
    KEY idx_session_sequence (session_id, sequence_no)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = '创作工作流消息表';

-- ------------------------------------------------------------
-- 16. 创作工作流步骤表
--     保存发布前优化等业务工作流的关键执行节点，用于后续失败回放和过程观测
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS creator_workflow_step
(
    id              BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    step_id         VARCHAR(64)  NOT NULL COMMENT '步骤唯一标识（UUID）',
    session_id      VARCHAR(64)  NOT NULL COMMENT '关联 creator_workflow_session.session_id',
    step_type       VARCHAR(32)  NOT NULL COMMENT '步骤类型：LOAD_CONTEXT=读取上下文，LLM_CALL=调用大模型，SAVE_RESULT=保存结果，CONFIRM_RESULT=确认结果',
    step_name       VARCHAR(128) NOT NULL COMMENT '步骤名称，用于前端和排障时快速理解当前节点',
    status          VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT '步骤状态：PENDING=待执行，RUNNING=执行中，SUCCESS=成功，FAILED=失败',
    input_summary   TEXT                  DEFAULT NULL COMMENT '输入摘要，只保存排障所需的简短说明，避免把完整材料重复塞进步骤表',
    output_summary  TEXT                  DEFAULT NULL COMMENT '输出摘要，用于快速判断本步骤产生了什么结果',
    raw_output      LONGTEXT              DEFAULT NULL COMMENT '原始输出，主要保存 LLM 返回内容，便于后续失败回放',
    error_message   VARCHAR(500)          DEFAULT NULL COMMENT '失败原因',
    start_time      DATETIME              DEFAULT NULL COMMENT '步骤开始时间',
    end_time        DATETIME              DEFAULT NULL COMMENT '步骤结束时间',
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    is_deleted      TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0=正常，1=已删除',
    UNIQUE KEY uk_step_id (step_id),
    KEY idx_session_id (session_id),
    KEY idx_session_create_time (session_id, create_time)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = '创作工作流步骤表';

-- ------------------------------------------------------------
-- 17. 评测用例表
--     保存 4.6 阶段用于人工评分和失败回放的样例任务，优先服务创作者工作流而不是抽象测试框架
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS creator_eval_case
(
    id              BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    case_id         VARCHAR(64)  NOT NULL COMMENT '评测用例唯一标识（稳定业务 ID）',
    user_id         VARCHAR(64)  NOT NULL DEFAULT 'default' COMMENT '用户标识，用于区分不同创作者的评测样本',
    case_name       VARCHAR(128) NOT NULL COMMENT '评测用例名称，方便在列表中快速识别',
    target_stage    VARCHAR(32)  NOT NULL COMMENT '评测阶段：PRE_PUBLISH=发布前优化，FEEDBACK=评论弹幕分析，REPORT=创作复盘报告',
    task_id         VARCHAR(64)           DEFAULT NULL COMMENT '可选关联的创作任务 ID，用于串起真实任务和评测样本',
    input_snapshot  LONGTEXT     NOT NULL COMMENT '评测输入快照，保存样例文本或 JSON，避免依赖外部输入',
    expected_points TEXT                  DEFAULT NULL COMMENT '人工期望命中要点 JSON 或文本，便于对照评分',
    scoring_rubric  TEXT                  DEFAULT NULL COMMENT '人工评分说明 JSON 或文本，方便统一评测口径',
    status          VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE' COMMENT '状态：ACTIVE=启用，ARCHIVED=已归档',
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    is_deleted      TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0=正常，1=已删除',
    UNIQUE KEY uk_case_id (case_id),
    KEY idx_user_stage_update_time (user_id, target_stage, update_time),
    KEY idx_task_id (task_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = '评测用例表';

-- ------------------------------------------------------------
-- 18. 评测结果表
--     保存每次评测的输出、耗时、token、失败原因和人工评分，支撑失败回放和样例复盘
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS creator_eval_result
(
    id                   BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    result_id            VARCHAR(64)  NOT NULL COMMENT '评测结果唯一标识（UUID）',
    case_id              VARCHAR(64)  NOT NULL COMMENT '关联 creator_eval_case.case_id',
    task_id              VARCHAR(64)           DEFAULT NULL COMMENT '关联创作任务 ID，若本次评测来自真实任务则记录',
    workflow_session_id  VARCHAR(64)           DEFAULT NULL COMMENT '关联工作流会话 ID，用于失败回放定位过程',
    target_stage         VARCHAR(32)  NOT NULL COMMENT '评测阶段：PRE_PUBLISH=发布前优化，FEEDBACK=评论弹幕分析，REPORT=创作复盘报告',
    model_name           VARCHAR(128)          DEFAULT NULL COMMENT '模型名称，记录本次评测用的是哪一个模型',
    prompt_version       VARCHAR(64)           DEFAULT NULL COMMENT 'Prompt 版本号，用于对比同一评测样例在不同提示词下的表现',
    prompt_hash          VARCHAR(64)           DEFAULT NULL COMMENT 'Prompt 快照 SHA-256 哈希，用于确认两次评测是否使用同一份提示词',
    prompt_snapshot      LONGTEXT              DEFAULT NULL COMMENT 'Prompt 快照，保存本次评测使用的 system/user 提示词或摘要，便于复现',
    output_summary       TEXT                  DEFAULT NULL COMMENT '输出摘要，方便列表页快速扫一眼结果',
    raw_output           LONGTEXT     NOT NULL COMMENT '模型原始输出或失败上下文，用于回放和对照',
    run_status           VARCHAR(32)  NOT NULL DEFAULT 'SUCCESS' COMMENT '运行状态：SUCCESS=成功，FAILED=失败',
    parse_status         VARCHAR(32)  NOT NULL DEFAULT 'RAW_ONLY' COMMENT '解析状态：PARSED=已结构化，RAW_ONLY=仅保留原文',
    elapsed_ms           BIGINT UNSIGNED       DEFAULT NULL COMMENT '耗时毫秒，用于成本和稳定性评估',
    prompt_tokens        INT UNSIGNED          DEFAULT NULL COMMENT '提示词 token 数',
    completion_tokens    INT UNSIGNED          DEFAULT NULL COMMENT '输出 token 数',
    total_tokens         INT UNSIGNED          DEFAULT NULL COMMENT '总 token 数',
    failure_reason       VARCHAR(500)          DEFAULT NULL COMMENT '失败原因，成功时可为空',
    readability_score    TINYINT UNSIGNED      DEFAULT NULL COMMENT '可读性评分，范围 1 到 5',
    relevance_score      TINYINT UNSIGNED      DEFAULT NULL COMMENT '贴合度评分，范围 1 到 5',
    completeness_score   TINYINT UNSIGNED      DEFAULT NULL COMMENT '完整性评分，范围 1 到 5',
    accuracy_score       TINYINT UNSIGNED      DEFAULT NULL COMMENT '准确性评分，范围 1 到 5',
    stability_score      TINYINT UNSIGNED      DEFAULT NULL COMMENT '稳定性评分，范围 1 到 5',
    cost_score           TINYINT UNSIGNED      DEFAULT NULL COMMENT '成本评分，范围 1 到 5',
    explainability_score TINYINT UNSIGNED      DEFAULT NULL COMMENT '可解释性评分，范围 1 到 5',
    reviewer_note        VARCHAR(1000)         DEFAULT NULL COMMENT '人工评测备注',
    create_time          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    is_deleted           TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0=正常，1=已删除',
    UNIQUE KEY uk_result_id (result_id),
    KEY idx_case_update_time (case_id, update_time),
    KEY idx_task_update_time (task_id, update_time),
    KEY idx_workflow_session_id (workflow_session_id),
    KEY idx_stage_prompt_version (target_stage, prompt_version, update_time)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = '评测结果表';

INSERT IGNORE INTO creator_eval_case (
    case_id,
    user_id,
    case_name,
    target_stage,
    task_id,
    input_snapshot,
    expected_points,
    scoring_rubric,
    status
)
VALUES
    (
        'eval-prepublish-title-001',
        'default',
        '发布前优化 - 标题表达克制样例',
        'PRE_PUBLISH',
        'sample-task-prepublish-001',
        '任务名称：标题表达优化样例；输入：标题草稿=Spring AI 创作工作台复盘；文稿=讲清任务管理、消息流和复盘闭环；字幕=无。',
        '["标题应直接说明视频价值","优先体现创作者工作流","给出标题风险提示"]',
        '["是否能输出可直接使用的标题建议","是否能说明为什么这样写更稳妥"]',
        'ACTIVE'
    ),
    (
        'eval-prepublish-feedback-002',
        'default',
        '发布前优化 - 结合创作者偏好样例',
        'PRE_PUBLISH',
        'sample-task-prepublish-002',
        '任务名称：偏好记忆样例；输入：标题草稿=这次怎么写标题更顺；文稿=希望标题面向技术学习者；字幕=补充了视频卖点。',
        '["要结合历史偏好","要给出标签和分区建议","要标出可能的标题党风险"]',
        '["是否读取到创作者偏好","是否把偏好和当前内容合并成可执行建议"]',
        'ACTIVE'
    ),
    (
        'eval-feedback-001',
        'default',
        '评论弹幕分析 - 观众误解样例',
        'FEEDBACK',
        'sample-task-feedback-001',
        '任务名称：反馈分析样例；输入：评论样例集中讨论“看不懂 Agent 流程”；弹幕样例里反复问“这个工具到底解决什么问题”。',
        '["需要识别误解点","需要区分提问和质疑","需要给出下一期回应建议"]',
        '["是否提炼出观众误解","是否给出可以直接执行的澄清动作"]',
        'ACTIVE'
    ),
    (
        'eval-feedback-002',
        'default',
        '评论弹幕分析 - 情绪聚合样例',
        'FEEDBACK',
        'sample-task-feedback-002',
        '任务名称：反馈聚合样例；输入：评论里同时存在正向认可、功能建议和少量抱怨，弹幕集中在关键演示节点。',
        '["要聚合高频观点","要给出情绪倾向总结","要指出争议点和建议回应"]',
        '["是否能把样例按主题聚类","是否能给出面向创作者的复盘动作"]',
        'ACTIVE'
    ),
    (
        'eval-report-001',
        'default',
        '创作复盘 - 竞品对照样例',
        'REPORT',
        'sample-task-report-001',
        '任务名称：复盘报告样例；输入：已有发布前建议、评论弹幕分析和竞品材料，需要汇总成一份创作复盘。',
        '["要汇总内容摘要","要说明竞品对照结论","要沉淀创作者偏好"]',
        '["是否覆盖复盘的完整章节","是否能把前置分析和竞品材料串起来"]',
        'ACTIVE'
    );

-- ------------------------------------------------------------
-- 19. 演示样例数据
--     这部分数据只服务 4.7 阶段的启动演示，目的是让首页一打开就能看到完整工作台闭环。
-- ------------------------------------------------------------
INSERT IGNORE INTO creator_task (
    id,
    task_id,
    user_id,
    task_name,
    status,
    create_time,
    update_time
)
VALUES
    (
        1001,
        'sample-task-prepublish-001',
        'default',
        '发布前优化样例 - 标题克制表达',
        'PRE_PUBLISH_ANALYZED',
        '2026-05-29 08:30:00',
        '2026-05-29 10:20:00'
    ),
    (
        1002,
        'sample-task-feedback-001',
        'default',
        '评论弹幕分析样例 - 观众误解与建议',
        'FEEDBACK_ANALYZED',
        '2026-05-30 08:30:00',
        '2026-05-30 10:10:00'
    ),
    (
        1003,
        'sample-task-report-001',
        'default',
        '创作复盘样例 - 全流程闭环演示',
        'ANALYZED',
        '2026-05-31 08:30:00',
        '2026-05-31 10:40:00'
    );

INSERT IGNORE INTO creator_material (
    id,
    task_id,
    material_type,
    content,
    create_time,
    update_time
)
VALUES
    (
        1101,
        'sample-task-prepublish-001',
        'TITLE_DRAFT',
        'Spring AI 创作工作台复盘',
        '2026-05-29 08:31:00',
        '2026-05-29 08:31:00'
    ),
    (
        1102,
        'sample-task-prepublish-001',
        'DESCRIPTION_DRAFT',
        '这期视频演示如何把创作任务、发布前优化和评论弹幕分析串成一个完整工作台。',
        '2026-05-29 08:31:20',
        '2026-05-29 08:31:20'
    ),
    (
        1103,
        'sample-task-prepublish-001',
        'MANUSCRIPT',
        '先说明项目为什么从通用 Agent 转向创作者工作台，再展示任务创建、标题建议、消息流确认和复盘总结。',
        '2026-05-29 08:31:40',
        '2026-05-29 08:31:40'
    ),
    (
        1104,
        'sample-task-prepublish-001',
        'SUBTITLE',
        '00:10 项目转向；01:02 任务创建；02:18 发布前优化；03:36 建议确认',
        '2026-05-29 08:32:00',
        '2026-05-29 08:32:00'
    ),
    (
        1201,
        'sample-task-feedback-001',
        'TITLE_DRAFT',
        '评论弹幕分析怎么帮助创作复盘',
        '2026-05-30 08:31:00',
        '2026-05-30 08:31:00'
    ),
    (
        1202,
        'sample-task-feedback-001',
        'DESCRIPTION_DRAFT',
        '这期视频会把评论、弹幕、情绪分布和误解点整理成后续选题建议。',
        '2026-05-30 08:31:20',
        '2026-05-30 08:31:20'
    ),
    (
        1203,
        'sample-task-feedback-001',
        'MANUSCRIPT',
        '这一期重点是让观众看懂评论弹幕分析到底能解决什么问题，先看输入样例，再看仪表盘和复盘报告。',
        '2026-05-30 08:31:40',
        '2026-05-30 08:31:40'
    ),
    (
        1204,
        'sample-task-feedback-001',
        'SUBTITLE',
        '00:08 输入样例；01:14 仪表盘；02:26 误解点；03:40 下一期建议',
        '2026-05-30 08:32:00',
        '2026-05-30 08:32:00'
    ),
    (
        1301,
        'sample-task-report-001',
        'TITLE_DRAFT',
        'Spring AI 创作工作台：把发布前优化、评论复盘和总结串起来',
        '2026-05-31 08:31:00',
        '2026-05-31 08:31:00'
    ),
    (
        1302,
        'sample-task-report-001',
        'DESCRIPTION_DRAFT',
        '这期视频展示一个面向 B 站创作者的 AI 工作台，重点是把材料输入、分析建议和复盘报告连成闭环。',
        '2026-05-31 08:31:20',
        '2026-05-31 08:31:20'
    ),
    (
        1303,
        'sample-task-report-001',
        'MANUSCRIPT',
        '本期从创作者视角介绍为什么要把发布前优化、评论弹幕分析和复盘报告放在一个工作台里，再展示任务管理、消息流、偏好记忆和结果确认。',
        '2026-05-31 08:31:40',
        '2026-05-31 08:31:40'
    ),
    (
        1304,
        'sample-task-report-001',
        'SUBTITLE',
        '00:12 任务管理；01:08 发布前优化；02:41 评论弹幕分析；04:05 复盘总结',
        '2026-05-31 08:32:00',
        '2026-05-31 08:32:00'
    );

INSERT IGNORE INTO creator_suggestion (
    id,
    suggestion_id,
    task_id,
    content_summary,
    audience_profile,
    selling_points,
    risk_points,
    title_suggestions,
    description_suggestion,
    tag_suggestions,
    partition_suggestion,
    raw_output,
    parse_status,
    create_time,
    update_time
)
VALUES
    (
        2101,
        'sample-suggestion-prepublish-001',
        'sample-task-prepublish-001',
        '视频围绕 Spring AI 创作工作台转型，适合展示 AI 应用开发能力和后端工程能力。',
        '目标观众是想做 AI 应用作品集的 Java 后端学习者，以及面试官。',
        '["把任务输入、建议生成和复盘串成一个闭环","强调创作者工作流而不是通用 Agent 炫技","让观众一眼看懂这个项目解决什么问题"]',
        '["标题信息量偏多","需要避免把技术细节说成抽象概念","开头要先讲创作者场景"]',
        '[{"title":"Spring AI 创作工作台复盘","reason":"直接点出项目主题，适合先建立场景","risk":"略偏技术向"},{"title":"一个 B 站创作者工作台，怎么把流程跑通","reason":"更像问题型标题，便于引出完整闭环","risk":"技术名露出较少"}]',
        '建议简介先说明这是一个面向创作者的 AI 工作台，再补一句它能做什么和为什么值得看。',
        '["Spring AI","创作工作台","B站创作者","AI应用","后端开发"]',
        'AI 工具教程',
        '{"contentSummary":"视频围绕 Spring AI 创作工作台转型，适合展示 AI 应用开发能力和后端工程能力。","audienceProfile":"目标观众是想做 AI 应用作品集的 Java 后端学习者，以及面试官。","sellingPoints":["把任务输入、建议生成和复盘串成一个闭环","强调创作者工作流而不是通用 Agent 炫技","让观众一眼看懂这个项目解决什么问题"],"riskPoints":["标题信息量偏多","需要避免把技术细节说成抽象概念","开头要先讲创作者场景"],"titleSuggestions":[{"title":"Spring AI 创作工作台复盘","reason":"直接点出项目主题，适合先建立场景","risk":"略偏技术向"},{"title":"一个 B 站创作者工作台，怎么把流程跑通","reason":"更像问题型标题，便于引出完整闭环","risk":"技术名露出较少"}],"descriptionSuggestion":"建议简介先说明这是一个面向创作者的 AI 工作台，再补一句它能做什么和为什么值得看。","tagSuggestions":["Spring AI","创作工作台","B站创作者","AI应用","后端开发"],"partitionSuggestion":"AI 工具教程"}',
        'PARSED',
        '2026-05-29 10:00:00',
        '2026-05-29 10:00:00'
    ),
    (
        2201,
        'sample-suggestion-feedback-001',
        'sample-task-feedback-001',
        '视频聚焦评论弹幕分析，帮助创作者理解观众误解点和改进方向。',
        '目标观众是想把评论复盘做成工作流的内容创作者。',
        '["能把评论和弹幕合并成统一视图","能把误解点直接转成下一期建议","能让复盘结果和创作动作连接起来"]',
        '["样例如果太少，统计会显得单薄","需要先说明分类是怎么来的","图表不能替代结论"]',
        '[{"title":"评论弹幕分析怎么帮助创作复盘","reason":"直接说明能力边界，适合复盘类视频","risk":"偏功能描述"},{"title":"看懂观众为什么说看不懂","reason":"更口语，能直接引出误解点","risk":"可能显得标题党"}]',
        '建议简介可以先讲清楚评论弹幕分析的目标，再补一条“最后会输出什么”。',
        '["评论分析","弹幕分析","创作复盘","观众反馈","内容优化"]',
        '内容分析',
        '{"contentSummary":"视频聚焦评论弹幕分析，帮助创作者理解观众误解点和改进方向。","audienceProfile":"目标观众是想把评论复盘做成工作流的内容创作者。","sellingPoints":["能把评论和弹幕合并成统一视图","能把误解点直接转成下一期建议","能让复盘结果和创作动作连接起来"],"riskPoints":["样例如果太少，统计会显得单薄","需要先说明分类是怎么来的","图表不能替代结论"],"titleSuggestions":[{"title":"评论弹幕分析怎么帮助创作复盘","reason":"直接说明能力边界，适合复盘类视频","risk":"偏功能描述"},{"title":"看懂观众为什么说看不懂","reason":"更口语，能直接引出误解点","risk":"可能显得标题党"}],"descriptionSuggestion":"建议简介可以先讲清楚评论弹幕分析的目标，再补一条“最后会输出什么”。","tagSuggestions":["评论分析","弹幕分析","创作复盘","观众反馈","内容优化"],"partitionSuggestion":"内容分析"}',
        'PARSED',
        '2026-05-30 10:00:00',
        '2026-05-30 10:00:00'
    ),
    (
        2301,
        'sample-suggestion-report-001',
        'sample-task-report-001',
        '视频围绕创作者工作台闭环展开，适合展示任务管理、偏好记忆和复盘能力。',
        '目标观众是想做 AI 应用作品集的 Java 后端学习者，以及面试官。',
        '["展示完整闭环而不是单点功能","把创作者偏好记忆作为亮点","能顺带说明 Agent、消息流和 SSE 的价值"]',
        '["闭环内容较多，容易讲散","要避免只讲框架名不讲场景","结尾需要明确下一步延伸"]',
        '[{"title":"Spring AI 创作工作台：把发布前优化、评论复盘和总结串起来","reason":"标题直接点出完整闭环，适合演示完整版","risk":"长度略长"},{"title":"面向 B 站创作者的 AI 工作台怎么做","reason":"更聚焦场景，方便面试官快速抓住主题","risk":"技术细节露出较少"}]',
        '建议简介先写清楚这个项目服务谁，再补一句它能帮助创作者做哪些动作。',
        '["Spring AI","B站创作者","创作复盘","Agent工作流","偏好记忆"]',
        'AI 工具教程',
        '{"contentSummary":"视频围绕创作者工作台闭环展开，适合展示任务管理、偏好记忆和复盘能力。","audienceProfile":"目标观众是想做 AI 应用作品集的 Java 后端学习者，以及面试官。","sellingPoints":["展示完整闭环而不是单点功能","把创作者偏好记忆作为亮点","能顺带说明 Agent、消息流和 SSE 的价值"],"riskPoints":["闭环内容较多，容易讲散","要避免只讲框架名不讲场景","结尾需要明确下一步延伸"],"titleSuggestions":[{"title":"Spring AI 创作工作台：把发布前优化、评论复盘和总结串起来","reason":"标题直接点出完整闭环，适合演示完整版","risk":"长度略长"},{"title":"面向 B 站创作者的 AI 工作台怎么做","reason":"更聚焦场景，方便面试官快速抓住主题","risk":"技术细节露出较少"}],"descriptionSuggestion":"建议简介先写清楚这个项目服务谁，再补一句它能帮助创作者做哪些动作。","tagSuggestions":["Spring AI","B站创作者","创作复盘","Agent工作流","偏好记忆"],"partitionSuggestion":"AI 工具教程"}',
        'PARSED',
        '2026-05-31 10:20:00',
        '2026-05-31 10:20:00'
    );

INSERT IGNORE INTO creator_user_feedback_detail (
    id,
    feedback_id,
    task_id,
    comment_samples,
    danmaku_samples,
    extra_context,
    create_time,
    update_time
)
VALUES
    (
        2401,
        'sample-feedback-detail-001',
        'sample-task-feedback-001',
        '1. 这次终于讲清楚评论弹幕分析怎么帮创作者复盘了。 2. 标题可以再短一点。 3. 希望把流程图放在开头。',
        '[00:12] 终于知道这个项目是干什么的 [01:05] 流程太清楚了 [02:18] 这个分类很有用 [03:40] 再讲快一点',
        '发布后两天收集的样例，主要集中在“看不懂 Agent 流程”和“标题是否太长”这两个点。',
        '2026-05-30 08:40:00',
        '2026-05-30 08:40:00'
    ),
    (
        2501,
        'sample-feedback-detail-002',
        'sample-task-report-001',
        '1. 这个工作台终于把创作者工作流串起来了。 2. 先说结果再讲技术会更好。 3. 偏好记忆这个点很加分。',
        '[00:10] 终于串起来了 [01:14] 这个流程我看懂了 [02:26] 偏好记忆很实用 [03:58] 先看结论再看细节',
        '完整版演示样例，重点围绕任务、消息流、偏好记忆和复盘报告。',
        '2026-05-31 08:40:00',
        '2026-05-31 08:40:00'
    );

INSERT IGNORE INTO creator_feedback_metric (
    id,
    metric_id,
    task_id,
    view_count,
    favorite_count,
    coin_count,
    like_count,
    share_count,
    source,
    create_time
)
VALUES
    (
        2403,
        'sample-feedback-metric-001',
        'sample-task-feedback-001',
        16800,
        420,
        88,
        730,
        56,
        'uploaded_json',
        '2026-05-30 08:41:00'
    ),
    (
        2503,
        'sample-feedback-metric-002',
        'sample-task-report-001',
        28600,
        680,
        122,
        1180,
        96,
        'uploaded_json',
        '2026-05-31 08:41:00'
    );

INSERT IGNORE INTO creator_feedback_item (
    id,
    item_id,
    task_id,
    source_type,
    source_id,
    content,
    occur_time_text,
    like_count,
    reply_count,
    category,
    sentiment,
    is_noise,
    reason,
    create_time
)
VALUES
    (
        2404,
        'sample-feedback-item-001',
        'sample-task-feedback-001',
        'COMMENT',
        NULL,
        '这次终于讲清楚这个工作台到底解决什么问题了。',
        '2026-05-30 20:10',
        58,
        6,
        'APPROVAL',
        'POSITIVE',
        0,
        '包含“讲清楚”“解决什么问题”，属于明确认可。',
        '2026-05-30 08:42:00'
    ),
    (
        2405,
        'sample-feedback-item-002',
        'sample-task-feedback-001',
        'COMMENT',
        NULL,
        '标题可以再短一点，先说工作台再说技术栈。',
        '2026-05-30 20:12',
        36,
        4,
        'SUGGESTION',
        'NEUTRAL',
        0,
        '包含“建议”“可以再”，属于明确建议。',
        '2026-05-30 08:42:20'
    ),
    (
        2406,
        'sample-feedback-item-003',
        'sample-task-feedback-001',
        'COMMENT',
        NULL,
        '这里的 Agent 流程有点多，看起来不太好跟。',
        '2026-05-30 20:18',
        19,
        2,
        'DOUBT',
        'NEGATIVE',
        0,
        '包含“看起来不太好跟”，属于疑问和质疑。',
        '2026-05-30 08:42:40'
    ),
    (
        2407,
        'sample-feedback-item-004',
        'sample-task-feedback-001',
        'COMMENT',
        NULL,
        '看懂了，发布前优化、评论分析和复盘报告是连着的。',
        '2026-05-30 20:21',
        43,
        5,
        'APPROVAL',
        'POSITIVE',
        0,
        '包含“看懂了”，属于正向共鸣。',
        '2026-05-30 08:43:00'
    ),
    (
        2408,
        'sample-feedback-item-005',
        'sample-task-feedback-001',
        'DANMAKU',
        NULL,
        '原来这个流程是先确认建议再进下一步',
        '00:01:14',
        NULL,
        NULL,
        'KNOWLEDGE_REACTION',
        'POSITIVE',
        0,
        '包含“原来”“流程”，适合知识反应。',
        '2026-05-30 08:43:20'
    ),
    (
        2409,
        'sample-feedback-item-006',
        'sample-task-feedback-001',
        'DANMAKU',
        NULL,
        '这个标题风险点讲得很实在',
        '00:02:03',
        NULL,
        NULL,
        'RESONANCE',
        'POSITIVE',
        0,
        '包含“实在”“风险点”，属于共鸣。',
        '2026-05-30 08:43:40'
    ),
    (
        2410,
        'sample-feedback-item-007',
        'sample-task-feedback-001',
        'DANMAKU',
        NULL,
        '这里看不懂，能不能再快一点',
        '00:03:01',
        NULL,
        NULL,
        'COMPLAINT',
        'NEGATIVE',
        0,
        '包含“看不懂”“快一点”，属于负向反馈。',
        '2026-05-30 08:44:00'
    ),
    (
        2411,
        'sample-feedback-item-008',
        'sample-task-feedback-001',
        'DANMAKU',
        NULL,
        '哈哈哈',
        '00:03:40',
        NULL,
        NULL,
        'EMPTY_MEANING',
        'NEUTRAL',
        1,
        '内容过短，先标记为无意义样例。',
        '2026-05-30 08:44:20'
    ),
    (
        2504,
        'sample-feedback-item-009',
        'sample-task-report-001',
        'COMMENT',
        NULL,
        '这次把任务管理、消息流和复盘串起来了，结构很完整。',
        '2026-05-31 20:11',
        72,
        8,
        'APPROVAL',
        'POSITIVE',
        0,
        '包含“结构很完整”，属于明确认可。',
        '2026-05-31 08:42:00'
    ),
    (
        2505,
        'sample-feedback-item-010',
        'sample-task-report-001',
        'COMMENT',
        NULL,
        '偏好记忆这个点很适合后面继续展开。',
        '2026-05-31 20:13',
        49,
        5,
        'SUGGESTION',
        'POSITIVE',
        0,
        '包含“适合后面继续展开”，属于建设性建议。',
        '2026-05-31 08:42:20'
    ),
    (
        2506,
        'sample-feedback-item-011',
        'sample-task-report-001',
        'COMMENT',
        NULL,
        '这里的 Agent 和工具调用还是有一点抽象。',
        '2026-05-31 20:15',
        28,
        3,
        'DOUBT',
        'NEGATIVE',
        0,
        '包含“有一点抽象”，属于疑问和质疑。',
        '2026-05-31 08:42:40'
    ),
    (
        2507,
        'sample-feedback-item-012',
        'sample-task-report-001',
        'COMMENT',
        NULL,
        '标题建议讲得克制一些就更像作品集项目了。',
        '2026-05-31 20:18',
        34,
        4,
        'SUGGESTION',
        'NEUTRAL',
        0,
        '包含“建议”“更像作品集项目”，属于改进建议。',
        '2026-05-31 08:43:00'
    ),
    (
        2508,
        'sample-feedback-item-013',
        'sample-task-report-001',
        'DANMAKU',
        NULL,
        '终于知道为什么要先做发布前优化',
        '00:01:08',
        NULL,
        NULL,
        'KNOWLEDGE_REACTION',
        'POSITIVE',
        0,
        '包含“终于知道”“为什么”，属于知识反应。',
        '2026-05-31 08:43:20'
    ),
    (
        2509,
        'sample-feedback-item-014',
        'sample-task-report-001',
        'DANMAKU',
        NULL,
        '这个闭环很清楚',
        '00:02:10',
        NULL,
        NULL,
        'RESONANCE',
        'POSITIVE',
        0,
        '包含“清楚”，属于正向共鸣。',
        '2026-05-31 08:43:40'
    ),
    (
        2510,
        'sample-feedback-item-015',
        'sample-task-report-001',
        'DANMAKU',
        NULL,
        '这里的节奏稍微快了一点',
        '00:03:12',
        NULL,
        NULL,
        'COMPLAINT',
        'NEGATIVE',
        0,
        '包含“快了一点”，属于负向反馈。',
        '2026-05-31 08:44:00'
    ),
    (
        2511,
        'sample-feedback-item-016',
        'sample-task-report-001',
        'DANMAKU',
        NULL,
        'Agent 工具调用终于能看懂了',
        '00:03:55',
        NULL,
        NULL,
        'KNOWLEDGE_REACTION',
        'POSITIVE',
        0,
        '包含“看懂了”，属于知识反应。',
        '2026-05-31 08:44:20'
    );

INSERT IGNORE INTO creator_llm_feedback_report (
    id,
    report_id,
    task_id,
    feedback_summary,
    hot_topics,
    sentiment_summary,
    controversy_points,
    misunderstanding_points,
    next_content_suggestions,
    interaction_suggestions,
    raw_output,
    parse_status,
    create_time,
    update_time
)
VALUES
    (
        2402,
        'sample-feedback-report-001',
        'sample-task-feedback-001',
        '观众最在意的是这个工作台到底解决什么问题，以及评论弹幕分析怎么转成下一步动作。',
        '[{"topic":"工作台价值","evidence":"评论里反复出现“解决什么问题”","suggestion":"开头先给出场景和结果"},{"topic":"标题长度","evidence":"多条评论提到标题偏长","suggestion":"把标题压缩成更直接的表达"},{"topic":"流程理解","evidence":"弹幕里多次问 Agent 流程","suggestion":"补一张流程示意图"}]',
        '整体情绪偏正向，但不少观众希望把流程讲得更直观一些。',
        '[{"point":"流程步骤偏多","risk":"观众会在中段失焦","responseAdvice":"先给总览，再分步展开"},{"point":"标题偏长","risk":"影响点击和记忆","responseAdvice":"标题先讲场景，再讲技术栈"}]',
        '[{"point":"Agent 流程抽象","clarificationAdvice":"先用一句话说明每一步在解决什么问题"},{"point":"标题党风险","clarificationAdvice":"先强调创作者工作流，再展示技术实现"}]',
        '["下一期可以专门讲流程图怎么设计","下一期可以补一个真实导入脚本示例","下一期可以展示评论如何转成复盘动作"]',
        '["先回答“这个项目解决什么问题”","把高频误解点放在视频中段重复一次","结尾给出一个可执行的下一步"]',
        '{"feedbackSummary":"观众最在意的是这个工作台到底解决什么问题，以及评论弹幕分析怎么转成下一步动作。","hotTopics":[{"topic":"工作台价值","evidence":"评论里反复出现“解决什么问题”","suggestion":"开头先给出场景和结果"},{"topic":"标题长度","evidence":"多条评论提到标题偏长","suggestion":"把标题压缩成更直接的表达"},{"topic":"流程理解","evidence":"弹幕里多次问 Agent 流程","suggestion":"补一张流程示意图"}],"sentimentSummary":"整体情绪偏正向，但不少观众希望把流程讲得更直观一些。","controversyPoints":[{"point":"流程步骤偏多","risk":"观众会在中段失焦","responseAdvice":"先给总览，再分步展开"},{"point":"标题偏长","risk":"影响点击和记忆","responseAdvice":"标题先讲场景，再讲技术栈"}],"misunderstandingPoints":[{"point":"Agent 流程抽象","clarificationAdvice":"先用一句话说明每一步在解决什么问题"},{"point":"标题党风险","clarificationAdvice":"先强调创作者工作流，再展示技术实现"}],"nextContentSuggestions":["下一期可以专门讲流程图怎么设计","下一期可以补一个真实导入脚本示例","下一期可以展示评论如何转成复盘动作"],"interactionSuggestions":["先回答“这个项目解决什么问题”","把高频误解点放在视频中段重复一次","结尾给出一个可执行的下一步"]}',
        'PARSED',
        '2026-05-30 09:20:00',
        '2026-05-30 09:20:00'
    ),
    (
        2502,
        'sample-feedback-report-002',
        'sample-task-report-001',
        '观众对这个闭环理解度较高，主要想确认偏好记忆、消息流和复盘报告之间的关系。',
        '[{"topic":"闭环完整性","evidence":"评论里直接认可“结构很完整”","suggestion":"把三个阶段串成一条主线"},{"topic":"偏好记忆","evidence":"观众专门提到这个点","suggestion":"解释它为什么能提升下一次创作"},{"topic":"Agent 与工具调用","evidence":"有人觉得这一段抽象","suggestion":"用任务状态图代替纯术语"}]',
        '整体情绪正向，少量观众希望把 Agent 和工具调用的作用说得更落地。',
        '[{"point":"Agent 和工具调用过于抽象","risk":"面试官和观众会把它看成纯名词","responseAdvice":"直接说它解决什么任务"},{"point":"节奏略快","risk":"重要概念容易被跳过","responseAdvice":"在关键节点加一句总结"}]',
        '[{"point":"偏好记忆作用不够直观","clarificationAdvice":"说明它如何影响下一次发布前优化"},{"point":"任务管理和消息流关系需要再明确","clarificationAdvice":"把任务、会话和步骤的关系画成一张图"}]',
        '["下一期可以专门讲偏好记忆最小闭环","下一期可以补工作流消息流的细节","下一期可以把竞品对照单独展开"]',
        '["先讲结果，再讲为什么需要这些模块","把偏好记忆作为复盘后的增益点","最后再补一句后续可以怎么扩展"]',
        '{"feedbackSummary":"观众对这个闭环理解度较高，主要想确认偏好记忆、消息流和复盘报告之间的关系。","hotTopics":[{"topic":"闭环完整性","evidence":"评论里直接认可“结构很完整”","suggestion":"把三个阶段串成一条主线"},{"topic":"偏好记忆","evidence":"观众专门提到这个点","suggestion":"解释它为什么能提升下一次创作"},{"topic":"Agent 与工具调用","evidence":"有人觉得这一段抽象","suggestion":"用任务状态图代替纯术语"}],"sentimentSummary":"整体情绪正向，少量观众希望把 Agent 和工具调用的作用说得更落地。","controversyPoints":[{"point":"Agent 和工具调用过于抽象","risk":"面试官和观众会把它看成纯名词","responseAdvice":"直接说它解决什么任务"},{"point":"节奏略快","risk":"重要概念容易被跳过","responseAdvice":"在关键节点加一句总结"}],"misunderstandingPoints":[{"point":"偏好记忆作用不够直观","clarificationAdvice":"说明它如何影响下一次发布前优化"},{"point":"任务管理和消息流关系需要再明确","clarificationAdvice":"把任务、会话和步骤的关系画成一张图"}],"nextContentSuggestions":["下一期可以专门讲偏好记忆最小闭环","下一期可以补工作流消息流的细节","下一期可以把竞品对照单独展开"],"interactionSuggestions":["先讲结果，再讲为什么需要这些模块","把偏好记忆作为复盘后的增益点","最后再补一句后续可以怎么扩展"]}',
        'PARSED',
        '2026-05-31 09:20:00',
        '2026-05-31 09:20:00'
    );

INSERT IGNORE INTO creator_competitor_sample (
    id,
    competitor_bv_id,
    competitor_video_name,
    task_id,
    category,
    competitor_samples,
    compare_dimension,
    extra_context,
    create_time,
    update_time
)
VALUES
    (
        2601,
        'BV1xK4y1z2Q9',
        '同类工作台拆解：如何把工具调用讲清楚',
        'sample-task-report-001',
        'AI 工具教程',
        '对方视频更强调框架和工具调用原理，我们这期更强调创作者场景、任务输入和复盘闭环。',
        '讲解节奏、闭环完整度、面试友好度',
        '作为对照样例，主要用于说明同类视频的表达方式和本项目的差异化方向。',
        '2026-05-31 09:00:00',
        '2026-05-31 09:00:00'
    );

INSERT IGNORE INTO creator_competitor_report (
    id,
    report_id,
    task_id,
    competitor_summary,
    competitor_advantages,
    own_advantages,
    own_disadvantages,
    gap_analysis,
    improvement_suggestions,
    differentiation_strategy,
    raw_output,
    parse_status,
    create_time,
    update_time
)
VALUES
    (
        2602,
        'sample-competitor-report-001',
        'sample-task-report-001',
        '竞品更偏工具链和工程细节，我们更偏创作者工作流和结果导向。',
        '["讲解更集中","技术点更密","适合快速建立工具印象"]',
        '["场景更明确","闭环更完整","偏好记忆更有作品集价值"]',
        '["术语更多","对非技术观众不够友好","没有把结果和创作动作连起来"]',
        '["竞品更像技术拆解，我们更像创作者工作台演示","我们在场景表达上更强，但需要再压缩术语密度"]',
        '["开头先给结果，再给流程图","把 Agent、消息流和 SSE 解释成创作流程的一部分","结尾补一个一句话总结"]',
        'B站创作者工作台应该把“能解决什么”说在前面，把“怎么做”放在后面。',
        '{"competitorSummary":"竞品更偏工具链和工程细节，我们更偏创作者工作流和结果导向。","competitorAdvantages":["讲解更集中","技术点更密","适合快速建立工具印象"],"ownAdvantages":["场景更明确","闭环更完整","偏好记忆更有作品集价值"],"ownDisadvantages":["术语更多","对非技术观众不够友好","没有把结果和创作动作连起来"],"gapAnalysis":["竞品更像技术拆解，我们更像创作者工作台演示","我们在场景表达上更强，但需要再压缩术语密度"],"improvementSuggestions":["开头先给结果，再给流程图","把 Agent、消息流和 SSE 解释成创作流程的一部分","结尾补一个一句话总结"],"differentiationStrategy":"B站创作者工作台应该把“能解决什么”说在前面，把“怎么做”放在后面。"}',
        'PARSED',
        '2026-05-31 09:20:00',
        '2026-05-31 09:20:00'
    );

INSERT IGNORE INTO creator_report (
    id,
    report_id,
    task_id,
    content_summary,
    core_selling_points,
    title_description_review,
    audience_feedback_summary,
    competitor_comparison,
    controversy_and_misunderstanding,
    next_action_suggestions,
    creator_preference_insight,
    overall_conclusion,
    raw_output,
    parse_status,
    create_time,
    update_time
)
VALUES
    (
        2701,
        'sample-creator-report-001',
        'sample-task-report-001',
        '本期演示围绕创作者工作台闭环展开，核心是把任务输入、发布前优化、评论复盘和长期偏好串起来。',
        '["任务输入很清楚","发布前优化结果可确认","评论弹幕分析能直接转动作","偏好记忆能反哺下一次创作"]',
        '{"titleConclusion":"标题直接点出创作者工作台闭环，适合作品集演示。","descriptionConclusion":"简介已经说明项目面向谁，但还可以再补一句它解决什么问题。","tagAndPartitionConclusion":"标签和分区都贴近 AI 工具教程和后端学习者场景。","riskReminder":"注意在开头先讲场景，否则容易只剩下技术名词。"}',
        '观众整体理解度较高，主要希望把偏好记忆和消息流之间的关系说得更直观。',
        '{"benchmarkConclusion":"竞品更偏工具链，我们更偏创作者工作流。","ownAdvantages":["场景更明确","闭环更完整","偏好记忆更有作品集价值"],"ownDisadvantages":["术语更多","对非技术观众不够友好"],"differentiationStrategy":"把“能解决什么”放在前面，把“怎么做”放在后面。"}',
        '[{"point":"Agent 和工具调用过于抽象","impact":"观众容易只记住术语","action":"先用任务流解释它们的作用"},{"point":"标题稍长","impact":"影响第一眼记忆","action":"再压缩一版更克制的标题"}]',
        '[{"suggestion":"下一期专门讲偏好记忆最小闭环","reason":"观众已经对这个点有兴趣","priority":"HIGH"},{"suggestion":"把工作流消息流单独拆一条短视频","reason":"帮助观众理解会话和步骤的关系","priority":"MEDIUM"}]',
        '["标题要先说明创作者场景","发布前优化建议要先给结论再给理由","复盘报告要尽量和下一期动作挂钩"]',
        '这是一个适合面试展示的 AI 应用闭环，关键不是功能堆叠，而是把每一步都解释成创作者能直接理解的动作。',
        '{"contentSummary":"本期演示围绕创作者工作台闭环展开，核心是把任务输入、发布前优化、评论复盘和长期偏好串起来。","coreSellingPoints":["任务输入很清楚","发布前优化结果可确认","评论弹幕分析能直接转动作","偏好记忆能反哺下一次创作"],"titleDescriptionReview":{"titleConclusion":"标题直接点出创作者工作台闭环，适合作品集演示。","descriptionConclusion":"简介已经说明项目面向谁，但还可以再补一句它解决什么问题。","tagAndPartitionConclusion":"标签和分区都贴近 AI 工具教程和后端学习者场景。","riskReminder":"注意在开头先讲场景，否则容易只剩下技术名词。"},"audienceFeedbackSummary":"观众整体理解度较高，主要希望把偏好记忆和消息流之间的关系说得更直观。","competitorComparison":{"benchmarkConclusion":"竞品更偏工具链，我们更偏创作者工作流。","ownAdvantages":["场景更明确","闭环更完整","偏好记忆更有作品集价值"],"ownDisadvantages":["术语更多","对非技术观众不够友好"],"differentiationStrategy":"把“能解决什么”放在前面，把“怎么做”放在后面。"},"controversyAndMisunderstanding":[{"point":"Agent 和工具调用过于抽象","impact":"观众容易只记住术语","action":"先用任务流解释它们的作用"},{"point":"标题稍长","impact":"影响第一眼记忆","action":"再压缩一版更克制的标题"}],"nextActionSuggestions":[{"suggestion":"下一期专门讲偏好记忆最小闭环","reason":"观众已经对这个点有兴趣","priority":"HIGH"},{"suggestion":"把工作流消息流单独拆一条短视频","reason":"帮助观众理解会话和步骤的关系","priority":"MEDIUM"}],"creatorPreferenceInsight":["标题要先说明创作者场景","发布前优化建议要先给结论再给理由","复盘报告要尽量和下一期动作挂钩"],"overallConclusion":"这是一个适合面试展示的 AI 应用闭环，关键不是功能堆叠，而是把每一步都解释成创作者能直接理解的动作。"}',
        'PARSED',
        '2026-05-31 09:50:00',
        '2026-05-31 09:50:00'
    );

INSERT IGNORE INTO creator_preference (
    id,
    preference_id,
    user_id,
    source_task_id,
    source_report_id,
    preference_content,
    create_time,
    update_time
)
VALUES
    (
        2801,
        'sample-preference-001',
        'default',
        'sample-task-report-001',
        'sample-creator-report-001',
        '["标题要先说明创作者场景","发布前优化建议要先给结论再给理由","复盘报告要尽量和下一期动作挂钩"]',
        '2026-05-31 09:51:00',
        '2026-05-31 09:51:00'
    );

INSERT IGNORE INTO creator_workflow_session (
    id,
    session_id,
    task_id,
    stage,
    status,
    user_id,
    confirmed_result_id,
    create_time,
    update_time
)
VALUES
    (
        2901,
        'sample-wf-report-001',
        'sample-task-report-001',
        'PRE_PUBLISH',
        'CONFIRMED',
        'default',
        'sample-suggestion-report-001',
        '2026-05-31 09:00:00',
        '2026-05-31 09:30:00'
    );

INSERT IGNORE INTO creator_workflow_message (
    id,
    message_id,
    session_id,
    role,
    content,
    content_type,
    detail_ref_type,
    detail_ref_id,
    sequence_no,
    create_time
)
VALUES
    (
        2902,
        'sample-wf-message-001',
        'sample-wf-report-001',
        'SYSTEM',
        '已进入发布前优化阶段。',
        'TEXT',
        NULL,
        NULL,
        1,
        '2026-05-31 09:00:10'
    ),
    (
        2903,
        'sample-wf-message-002',
        'sample-wf-report-001',
        'SYSTEM',
        '已读取任务：创作复盘样例 - 全流程闭环演示。',
        'TEXT',
        NULL,
        NULL,
        2,
        '2026-05-31 09:00:20'
    ),
    (
        2904,
        'sample-wf-message-003',
        'sample-wf-report-001',
        'SYSTEM',
        '已加载标题草稿，约 21 字，点击查看详情。',
        'MATERIAL_SUMMARY',
        'MATERIAL',
        '1301',
        3,
        '2026-05-31 09:00:30'
    ),
    (
        2905,
        'sample-wf-message-004',
        'sample-wf-report-001',
        'SYSTEM',
        '已加载简介草稿，约 47 字，点击查看详情。',
        'MATERIAL_SUMMARY',
        'MATERIAL',
        '1302',
        4,
        '2026-05-31 09:00:40'
    ),
    (
        2906,
        'sample-wf-message-005',
        'sample-wf-report-001',
        'SYSTEM',
        '已加载文稿，约 58 字，点击查看详情。',
        'MATERIAL_SUMMARY',
        'MATERIAL',
        '1303',
        5,
        '2026-05-31 09:00:50'
    ),
    (
        2907,
        'sample-wf-message-006',
        'sample-wf-report-001',
        'SYSTEM',
        '已加载字幕，约 34 字，点击查看详情。',
        'MATERIAL_SUMMARY',
        'MATERIAL',
        '1304',
        6,
        '2026-05-31 09:01:00'
    ),
    (
        2908,
        'sample-wf-message-007',
        'sample-wf-report-001',
        'AGENT',
        '我会先提炼内容卖点，再检查标题、简介和标签的表达风险。',
        'TEXT',
        NULL,
        NULL,
        7,
        '2026-05-31 09:01:10'
    ),
    (
        2909,
        'sample-wf-message-008',
        'sample-wf-report-001',
        'RESULT',
        '已生成发布前优化建议，建议先检查标题、简介和标签，再点击采用本轮建议。',
        'RESULT_CARD',
        'SUGGESTION',
        'sample-suggestion-report-001',
        8,
        '2026-05-31 09:02:00'
    ),
    (
        2910,
        'sample-wf-message-009',
        'sample-wf-report-001',
        'SYSTEM',
        '已采用本轮发布前优化建议，后续可以进入评论弹幕分析阶段。',
        'TEXT',
        NULL,
        NULL,
        9,
        '2026-05-31 09:02:10'
    );

INSERT IGNORE INTO creator_workflow_step (
    id,
    step_id,
    session_id,
    step_type,
    step_name,
    status,
    input_summary,
    output_summary,
    raw_output,
    error_message,
    start_time,
    end_time,
    create_time
)
VALUES
    (
        2911,
        'sample-wf-step-001',
        'sample-wf-report-001',
        'LOAD_CONTEXT',
        '读取创作任务材料',
        'SUCCESS',
        '任务材料数量：4',
        '已读取 4 份用户主动提供的材料。',
        NULL,
        NULL,
        '2026-05-31 09:00:15',
        '2026-05-31 09:00:18',
        '2026-05-31 09:00:15'
    ),
    (
        2912,
        'sample-wf-step-002',
        'sample-wf-report-001',
        'LLM_CALL',
        '生成发布前优化建议',
        'SUCCESS',
        '基于任务材料、创作指导和工作流补充消息调用 LLM。',
        'LLM 已返回发布前优化建议，解析状态：PARSED',
        '{"contentSummary":"视频围绕创作者工作台闭环展开，适合展示任务管理、偏好记忆和复盘能力。","audienceProfile":"目标观众是想做 AI 应用作品集的 Java 后端学习者，以及面试官。","sellingPoints":["展示完整闭环而不是单点功能","把创作者偏好记忆作为亮点","能顺带说明 Agent、消息流和 SSE 的价值"],"riskPoints":["闭环内容较多，容易讲散","要避免只讲框架名不讲场景","结尾需要明确下一步延伸"],"titleSuggestions":[{"title":"Spring AI 创作工作台：把发布前优化、评论复盘和总结串起来","reason":"标题直接点出完整闭环，适合演示完整版","risk":"长度略长"},{"title":"面向 B 站创作者的 AI 工作台怎么做","reason":"更聚焦场景，方便面试官快速抓住主题","risk":"技术细节露出较少"}],"descriptionSuggestion":"建议简介先写清楚这个项目服务谁，再补一句它能帮助创作者做哪些动作。","tagSuggestions":["Spring AI","B站创作者","创作复盘","Agent工作流","偏好记忆"],"partitionSuggestion":"AI 工具教程"}',
        NULL,
        '2026-05-31 09:00:20',
        '2026-05-31 09:02:00',
        '2026-05-31 09:00:20'
    ),
    (
        2913,
        'sample-wf-step-003',
        'sample-wf-report-001',
        'SAVE_RESULT',
        '保存建议结果消息',
        'SUCCESS',
        '把结构化建议挂到当前工作流会话，等待用户确认。',
        '建议结果消息已保存，suggestionId=sample-suggestion-report-001',
        NULL,
        NULL,
        '2026-05-31 09:01:50',
        '2026-05-31 09:02:05',
        '2026-05-31 09:01:50'
    ),
    (
        2914,
        'sample-wf-step-004',
        'sample-wf-report-001',
        'CONFIRM_RESULT',
        '确认发布前优化建议',
        'SUCCESS',
        '用户确认 suggestionId=sample-suggestion-report-001',
        '任务状态已推进为 PRE_PUBLISH_ANALYZED',
        NULL,
        NULL,
        '2026-05-31 09:02:08',
        '2026-05-31 09:02:10',
        '2026-05-31 09:02:08'
    );

INSERT IGNORE INTO creator_eval_result (
    id,
    result_id,
    case_id,
    task_id,
    workflow_session_id,
    target_stage,
    model_name,
    prompt_version,
    prompt_hash,
    prompt_snapshot,
    output_summary,
    raw_output,
    run_status,
    parse_status,
    elapsed_ms,
    prompt_tokens,
    completion_tokens,
    total_tokens,
    failure_reason,
    readability_score,
    relevance_score,
    completeness_score,
    accuracy_score,
    stability_score,
    cost_score,
    explainability_score,
    reviewer_note,
    create_time,
    update_time
)
VALUES
    (
        3001,
        'sample-eval-result-001',
        'eval-report-001',
        'sample-task-report-001',
        'sample-wf-report-001',
        'REPORT',
        'qwen3',
        'report-v1-demo',
        '3454e78174c8fb4d434697d57ba247371b9785a1cee1ddf01697ba0073ae5405',
        'system: 你是面向 B 站创作者的复盘助手；user: 汇总发布前建议、评论弹幕反馈和竞品分析，输出结构化复盘报告。',
        '完整复盘已生成，重点强调创作者工作流、偏好记忆和评论复盘之间的闭环。',
        '{"contentSummary":"本期演示围绕创作者工作台闭环展开，核心是把任务输入、发布前优化、评论复盘和长期偏好串起来。","overallConclusion":"这是一个适合面试展示的 AI 应用闭环，关键不是功能堆叠，而是把每一步都解释成创作者能直接理解的动作。"}',
        'SUCCESS',
        'PARSED',
        1280,
        1024,
        412,
        1436,
        NULL,
        5,
        5,
        5,
        5,
        4,
        4,
        5,
        '样例结果已覆盖完整闭环，可作为演示页面的默认评测记录。',
        '2026-05-31 09:55:00',
        '2026-05-31 09:55:00'
    );
