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
    video_type  VARCHAR(64)  NOT NULL DEFAULT '未分类' COMMENT '视频类型，用于按创作赛道加载对应语境库',
    status      VARCHAR(32)  NOT NULL DEFAULT 'DRAFT' COMMENT '任务状态：DRAFT=草稿，PRE_PUBLISH_ANALYZED=已完成发布前分析，FEEDBACK_ANALYZED=已完成反馈分析，COMPETITOR_ANALYZED=已完成竞品分析，ANALYZED=已完成复盘，ARCHIVED=已归档',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    is_deleted  TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0=正常，1=已删除',
    UNIQUE KEY uk_task_id (task_id),
    KEY idx_user_id (user_id),
    KEY idx_user_video_type_update_time (user_id, video_type, update_time),
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
    creator_dilemma        TEXT                  DEFAULT NULL COMMENT '创作者困境，用于记录本期最容易让 UP 主纠结或做错的表达问题',
    audience_profile       TEXT                  DEFAULT NULL COMMENT '目标受众判断',
    audience_hook          TEXT                  DEFAULT NULL COMMENT '观众点击、继续观看或收藏评论的核心动机',
    content_positioning    TEXT                  DEFAULT NULL COMMENT '内容定位与差异化方向，不保存编造的平台推荐结论',
    selling_points         TEXT                  DEFAULT NULL COMMENT '核心卖点列表 JSON',
    risk_points            TEXT                  DEFAULT NULL COMMENT '风险点列表 JSON',
    title_suggestions      TEXT                  DEFAULT NULL COMMENT '标题建议列表 JSON',
    description_suggestion TEXT                  DEFAULT NULL COMMENT '简介建议',
    actionable_revision_plan TEXT                DEFAULT NULL COMMENT '可执行修改计划 JSON，用于把建议落成标题、开头、简介等具体动作',
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
    creator_feedback_dilemma TEXT                 DEFAULT NULL COMMENT '本轮反馈暴露出的创作者复盘困境',
    audience_core_concern    TEXT                 DEFAULT NULL COMMENT '观众最集中的真实关注点和互动动机',
    misunderstanding_source_analysis TEXT         DEFAULT NULL COMMENT '误解来源分析列表 JSON',
    feedback_action_plan     TEXT                 DEFAULT NULL COMMENT '评论区回应、内容修正、下一期动作计划列表 JSON',
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
    -- 阶段 4.13 新增：向量索引状态字段。放在明细表而不是新建索引任务表，是因为本阶段只需知道当前任务哪些明细已可被向量检索，避免过度设计
    embedding_id          VARCHAR(128)          DEFAULT NULL COMMENT 'Milvus 文档 ID，默认复用 item_id，让向量文档与 MySQL 明细一一对应',
    embedding_status      VARCHAR(32)  NOT NULL DEFAULT 'PENDING' COMMENT '向量索引状态：PENDING=待索引，INDEXED=已索引，FAILED=失败，SKIPPED=跳过；默认 PENDING 不依赖 Milvus',
    embedding_error       VARCHAR(512)          DEFAULT NULL COMMENT '最近一次索引失败原因摘要，便于排查 Embedding 或 Milvus 异常；只存截断摘要不存完整堆栈',
    embedding_update_time DATETIME              DEFAULT NULL COMMENT '最近一次索引状态更新时间，未索引时为空',
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    is_deleted      TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0=正常，1=已删除',
    UNIQUE KEY uk_item_id (item_id),
    KEY idx_task_source_category (task_id, source_type, category),
    KEY idx_task_source_like (task_id, source_type, like_count),
    KEY idx_task_sentiment (task_id, sentiment),
    KEY idx_task_create_time (task_id, create_time),
    -- 反馈报告弹窗每次打开都会查索引状态，按 (task_id, embedding_status) 建索引让 GROUP BY 计数走索引
    KEY idx_task_embedding_status (task_id, embedding_status)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = '评论弹幕明细表';

-- ------------------------------------------
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

-- ------------------------------------------------------------
-- 13.2 创作者视频类型语境词条表
--      保存用户在不同视频类型下沉淀的关键词、黑话、标题套路和慎用表达
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS creator_context_term
(
    id              BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    term_id         VARCHAR(64)  NOT NULL COMMENT '语境词条唯一标识（UUID）',
    user_id         VARCHAR(64)  NOT NULL DEFAULT 'default' COMMENT '用户标识，用于隔离不同创作者的私有语境',
    video_type      VARCHAR(64)  NOT NULL COMMENT '视频类型：GLOBAL=全局通用，其它值对应具体创作赛道',
    term            VARCHAR(128) NOT NULL COMMENT '词条展示文本，例如关键词、黑话、标题套路或慎用表达',
    normalized_term VARCHAR(128) NOT NULL COMMENT '归一化词条，用于同类型下去重，避免大小写或空白导致重复',
    term_type       VARCHAR(32)  NOT NULL COMMENT '词条类型：KEYWORD=关键词，SLANG=小圈子黑话，MEME=梗，TABOO=慎用词，TITLE_PATTERN=标题套路，AUDIENCE_CONCERN=观众关注点',
    polarity        VARCHAR(16)  NOT NULL DEFAULT 'NEUTRAL' COMMENT '使用倾向：POSITIVE=推荐使用，NEGATIVE=慎用或避免，NEUTRAL=中性参考',
    source_type     VARCHAR(32)  NOT NULL DEFAULT 'USER_SAVE' COMMENT '来源类型：USER_SAVE=用户保存，AI_ACCEPTED=采纳AI建议，COMMENT_EXTRACTED=评论弹幕候选，USER_REJECTED=用户否定，VIDEO_SUCCESS=高质量历史视频',
    source_task_id  VARCHAR(64)           DEFAULT NULL COMMENT '来源任务ID，用于追溯词条来自哪一期内容',
    evidence_text   VARCHAR(1000)         DEFAULT NULL COMMENT '证据说明，用来解释为什么这个词适合或不适合该类型视频',
    weight          INT          NOT NULL DEFAULT 50 COMMENT '权重，越高越优先注入提示词，用户保存和采纳会提高权重',
    usage_count     INT          NOT NULL DEFAULT 0 COMMENT '使用次数，用于判断词条是否持续有效',
    accept_count    INT          NOT NULL DEFAULT 0 COMMENT '被用户接受次数，用于提高可信度',
    reject_count    INT          NOT NULL DEFAULT 0 COMMENT '被用户拒绝次数，用于降权或禁用',
    enabled         TINYINT      NOT NULL DEFAULT 1 COMMENT '是否启用：1=参与检索和提示词注入，0=保留记录但不再使用',
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    is_deleted      TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0=正常，1=已删除',
    UNIQUE KEY uk_context_term_id (term_id),
    UNIQUE KEY uk_context_identity (user_id, video_type, normalized_term, term_type),
    KEY idx_context_user_type_weight (user_id, video_type, enabled, weight, update_time),
    KEY idx_context_source_task (source_task_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = '创作者视频类型语境词条表';

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
    step_type       VARCHAR(32)  NOT NULL COMMENT '步骤类型：LOAD_CONTEXT=读取上下文，AGENT_REASONING=Agent推理，TOOL_CALL=工具调用，LLM_CALL=调用大模型，SAVE_RESULT=保存结果，CONFIRM_RESULT=确认结果',
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

-- ------------------------------------------------------------
-- 19. 跨分区视频案例主表（知识库父表，阶段 5.1 起用）
--     存储「优品标杆 + 竞品」视频案例，跨创作任务复用，作为 Agent 发布前优化 / 竞品分析时可检索的领域知识底座。
--     设计成父表是为 5.2 的「父子召回（small-to-big）」打底：召回后用本表扩展成完整案例卡片，优质评论弹幕明细见子表 creator_reference_video_item。
--     本表不挂在某个 creator_task 下，因为案例是跨任务、跨分区共享的，归属创作者的知识库而非单次任务。
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS creator_reference_video
(
    id                    BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    video_id              VARCHAR(64)     NOT NULL COMMENT '案例唯一标识（UUID）；跨任务稳定引用，同时作为子表外键和向量文档 ID 的来源',
    bv_id                 VARCHAR(20)              DEFAULT NULL COMMENT 'B 站 BV 号；seed 内置样例或手动录入可能没有，故可空',
    tier                  VARCHAR(16)     NOT NULL DEFAULT 'BENCHMARK' COMMENT '案例层级：BENCHMARK=优品标杆（榜单来源），COMPETITOR=竞品，OWN_HISTORY=创作者历史；决定检索时如何使用该案例',
    category              VARCHAR(64)              DEFAULT NULL COMMENT '分区 / 主题，用于同赛道过滤检索；质量分也按分区归一化，缺失时不参与分区相对打分',
    title                 VARCHAR(255)             DEFAULT NULL COMMENT '视频标题，案例卡片的语义主体之一；导入接口负责校验必填，DB 层保持宽松以容纳 seed / 手动录入',
    description           LONGTEXT                 DEFAULT NULL COMMENT '视频简介原文，案例卡片的语义主体之一',
    tags                  VARCHAR(512)             DEFAULT NULL COMMENT '标签 JSON 数组（如 ["AI","教程"]），用于主题匹配和检索过滤',
    view_count            BIGINT UNSIGNED          DEFAULT NULL COMMENT '播放量，质量打分的分母；缺失或为 0 时不打分（quality_score 置空）',
    like_count            BIGINT UNSIGNED          DEFAULT NULL COMMENT '点赞量，质量打分互动率分子之一；离线脚本未取到时为空',
    coin_count            BIGINT UNSIGNED          DEFAULT NULL COMMENT '投币量，质量信号最强、打分权重最高；缺失为空',
    favorite_count        BIGINT UNSIGNED          DEFAULT NULL COMMENT '收藏量，质量打分互动率分子之一；缺失为空',
    danmaku_count         BIGINT UNSIGNED          DEFAULT NULL COMMENT '弹幕量，质量打分互动率分子之一；缺失为空',
    reply_count           BIGINT UNSIGNED          DEFAULT NULL COMMENT '评论量，质量打分互动率分子之一；缺失为空',
    highlight_summary     LONGTEXT                 DEFAULT NULL COMMENT '清洗后优质评论 / 弹幕的亮点摘要，由小模型汇总，作为案例卡片语义主体之一（5.1c 生成，5.1a 为空）',
    quality_score         DECIMAL(6, 2)            DEFAULT NULL COMMENT '分区归一化质量分（0–100，v1 公式产出）；view 缺失或分区样本不足时为空（5.1c 计算）',
    source                VARCHAR(64)     NOT NULL DEFAULT 'seed' COMMENT '数据来源：bilibili_rank_daily / weekly / monthly=榜单，manual_bv=手动指定 BV，seed=内置样例',
    publish_time_text     VARCHAR(64)              DEFAULT NULL COMMENT '发布时间文本；仅作展示用、不做时间运算，故存文本而非 DATETIME',
    -- 向量索引状态字段，完全沿用 creator_feedback_item 的范式：默认 PENDING 不依赖 Milvus，5.1a 阶段全部停留在 PENDING
    embedding_id          VARCHAR(128)             DEFAULT NULL COMMENT 'Milvus 文档 ID，默认复用 video_id，让向量文档与案例主表一一对应',
    embedding_status      VARCHAR(32)     NOT NULL DEFAULT 'PENDING' COMMENT '向量索引状态：PENDING=待索引，INDEXED=已索引，FAILED=失败，SKIPPED=跳过；默认 PENDING 不依赖 Milvus',
    embedding_error       VARCHAR(512)             DEFAULT NULL COMMENT '最近一次索引失败原因摘要，便于排查 Embedding 或 Milvus 异常；只存截断摘要不存完整堆栈',
    embedding_update_time DATETIME                 DEFAULT NULL COMMENT '最近一次索引状态更新时间，未索引时为空',
    create_time           DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time           DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    is_deleted            TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除：0=正常，1=已删除',
    UNIQUE KEY uk_video_id (video_id),
    -- 同赛道检索主路径：按分区 + 层级过滤（例如「知识区·科技」下的 BENCHMARK 案例）
    KEY idx_category_tier (category, tier),
    KEY idx_bv_id (bv_id),
    -- 索引重建按状态批量扫描待索引案例，单列索引支撑 WHERE embedding_status = 'PENDING'
    KEY idx_embedding_status (embedding_status)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = '跨分区视频案例主表';

-- ------------------------------------------------------------
-- 20. 跨分区视频案例主题中块表（三层分块中间层）
--     位于父表整张案例卡片和子表原始评论弹幕之间，承载标题包装、内容定位、观众反馈等创作者常问的主题。
--     这样检索能先命中“这条视频在哪个创作维度值得参考”，再回到父视频和原始证据。
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS creator_reference_video_chunk
(
    id                    BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    chunk_id              VARCHAR(64)  NOT NULL COMMENT '主题中块唯一标识（UUID）；作为中块向量文档 ID，便于和 Milvus 文档一一对应',
    video_id              VARCHAR(64)  NOT NULL COMMENT '关联 creator_reference_video.video_id；中块命中后用它回到完整视频案例',
    chunk_type            VARCHAR(32)  NOT NULL COMMENT '中块类型：TITLE_PACKAGE=标题包装，CONTENT_POSITIONING=内容定位，AUDIENCE_FEEDBACK_SUMMARY=观众反馈主题；类型化是为了让检索能区分创作者问题属于哪个维度',
    chunk_title           VARCHAR(128)          DEFAULT NULL COMMENT '中块展示标题；用于解释这个中块为什么被召回',
    chunk_content         LONGTEXT     NOT NULL COMMENT '中块正文；由标题、简介、标签、亮点摘要和已清洗反馈确定性拼装，不额外让 LLM 编造新结论',
    source_item_ids       LONGTEXT              DEFAULT NULL COMMENT '来源子条目 item_id 的 JSON 数组；只有观众反馈主题块会填写，用于后续追踪原始评论弹幕来源',
    embedding_id          VARCHAR(128)          DEFAULT NULL COMMENT 'Milvus 文档 ID，默认复用 chunk_id，让向量文档与主题中块一一对应',
    embedding_status      VARCHAR(32)  NOT NULL DEFAULT 'PENDING' COMMENT '向量索引状态：PENDING=待索引，INDEXED=已索引，FAILED=失败，SKIPPED=跳过；默认 PENDING 不依赖 Milvus',
    embedding_error       VARCHAR(512)          DEFAULT NULL COMMENT '最近一次索引失败原因摘要，便于排查 Embedding 或 Milvus 异常；只存截断摘要不存完整堆栈',
    embedding_update_time DATETIME              DEFAULT NULL COMMENT '最近一次索引状态更新时间，未索引时为空',
    create_time           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    is_deleted            TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0=正常，1=已删除',
    UNIQUE KEY uk_chunk_id (chunk_id),
    -- 当前每个视频每类主题只保留一个中块，唯一约束能防止历史补齐或重复导入产生重复主题块
    UNIQUE KEY uk_video_chunk_type (video_id, chunk_type),
    -- 索引重建按状态批量扫描待索引主题中块
    KEY idx_chunk_embedding_status (embedding_status)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = '跨分区视频案例主题中块表';

-- ------------------------------------------------------------
-- 21. 跨分区视频案例优质评论弹幕子表（知识库子表，阶段 5.1 起用）
--     结构对标 creator_feedback_item，但外键是 video_id（跨任务）而非 task_id。
--     只保留清洗后「非噪声且正 / 负向」的优质短文本，为 5.2 的父子召回（small-to-big）提供可被精确召回的子文档。
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS creator_reference_video_item
(
    id                    BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    item_id               VARCHAR(64)  NOT NULL COMMENT '明细唯一标识（UUID）',
    video_id              VARCHAR(64)  NOT NULL COMMENT '关联 creator_reference_video.video_id；跨创作任务，不挂在某个 task 下',
    source_type           VARCHAR(16)  NOT NULL COMMENT '来源类型：COMMENT=评论，DANMAKU=弹幕',
    content               LONGTEXT     NOT NULL COMMENT '评论或弹幕原文，来自离线脚本产出的原始全量，入库时清洗筛选',
    sentiment             VARCHAR(16)  NOT NULL DEFAULT 'NEUTRAL' COMMENT '情绪倾向：POSITIVE=正向，NEGATIVE=负向，NEUTRAL=中性；本表只落 POSITIVE / NEGATIVE（中性灌水在清洗时丢弃）',
    is_noise              TINYINT      NOT NULL DEFAULT 0 COMMENT '是否无意义内容：0=有效，1=无意义或重复；本表只落 0，保留字段以便排查清洗逻辑',
    like_count            BIGINT UNSIGNED       DEFAULT NULL COMMENT '评论点赞量，点赞越多通常代表更多观众共鸣；弹幕或缺失为空',
    reply_count           INT UNSIGNED          DEFAULT NULL COMMENT '评论回复量，回复越多通常代表更多互动或争议；弹幕或缺失为空',
    occur_time_text       VARCHAR(64)           DEFAULT NULL COMMENT '弹幕出现时间或评论发布时间文本，用于证据展示',
    reason                VARCHAR(500)          DEFAULT NULL COMMENT '清洗分类原因，说明为什么判为此情绪或保留为优质条目',
    -- 向量索引状态字段，完全沿用 creator_feedback_item 范式；5.1a 不接 Embedding，全部停留在 PENDING
    embedding_id          VARCHAR(128)          DEFAULT NULL COMMENT 'Milvus 文档 ID，默认复用 item_id，让向量文档与明细一一对应',
    embedding_status      VARCHAR(32)  NOT NULL DEFAULT 'PENDING' COMMENT '向量索引状态：PENDING=待索引，INDEXED=已索引，FAILED=失败，SKIPPED=跳过；默认 PENDING 不依赖 Milvus',
    embedding_error       VARCHAR(512)          DEFAULT NULL COMMENT '最近一次索引失败原因摘要，便于排查 Embedding 或 Milvus 异常；只存截断摘要不存完整堆栈',
    embedding_update_time DATETIME              DEFAULT NULL COMMENT '最近一次索引状态更新时间，未索引时为空',
    create_time           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    is_deleted            TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0=正常，1=已删除',
    UNIQUE KEY uk_item_id (item_id),
    -- 父子召回与情绪聚合主路径：按案例 + 情绪过滤优质条目
    KEY idx_video_sentiment (video_id, sentiment),
    -- 索引重建按状态批量扫描某案例下待索引明细
    KEY idx_video_embedding_status (video_id, embedding_status)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = '跨分区视频案例优质评论弹幕子表';

-- ------------------------------------------------------------
-- 22. 提示词模板表（阶段 5.5 起用）
--     把原本硬编码在各 Service 里的大模型提示词搬到数据库，调用方按 prompt_key 取词，
--     支持运行期热更新和前端自定义，避免改一句提示词就要改代码、重新打包发布。
--     版本追踪不在本表：评测结果表 creator_eval_result 已自带 prompt_hash / prompt_snapshot，
--     评测时会把当时用的提示词快照下来，故本表只存「当前生效的这一版」，不做版本 / AB 表。
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS llm_prompt_template
(
    id          BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    prompt_key  VARCHAR(128) NOT NULL COMMENT '提示词唯一键，调用方用它取词，命名用「场景.类型」如 pre_publish.system',
    prompt_type VARCHAR(16)  NOT NULL COMMENT '提示词类型：SYSTEM=系统角色设定，USER=用户输入模板',
    scene       VARCHAR(64)  NOT NULL COMMENT '所属业务场景，给前端分组展示用，如 发布前优化、评论弹幕分析、创作复盘',
    content     LONGTEXT     NOT NULL COMMENT '提示词正文；USER 类型里用 {名字} 表示运行期才填入的变量（5.5-3 起）',
    description VARCHAR(255)          DEFAULT NULL COMMENT '这条提示词的用途说明，给前端编辑者看懂它是干嘛的',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间，热更新改正文时刷新',
    is_deleted  TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0=正常，1=已删除',
    UNIQUE KEY uk_prompt_key (prompt_key),
    KEY idx_scene (scene)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = 'LLM 提示词模板表';

-- 阶段 6：PaE / Multi Agent 新增提示词种子。重复执行时不覆盖 content，避免把设置面板中人工调优过的提示词重置掉
INSERT INTO llm_prompt_template (prompt_key, prompt_type, scene, content, description)
VALUES
    ('agent_plan_execute_planner.system', 'SYSTEM', '通用Agent-计划执行',
     '你是 LinkAgent 的 Plan-and-Execute Planner。你的任务是先理解用户目标，再基于工具清单生成可执行计划。必须输出 JSON 对象，字段匹配 AgentPlan：objective、steps、rationale、coverageCheck。steps 中每步字段为 id、description、action、actionInput、dependsOn、expectedObservation。action 必须来自工具清单，不允许编造工具；如果某个诉求不需要工具，请不要硬塞工具步骤，而是在 coverageCheck 说明将由 Synthesizer 直接回答。每个计划最多 5 步，优先选择必要步骤。可用工具：\n{toolList}',
     'PaE Planner：根据工具清单生成结构化执行计划'),
    ('agent_plan_execute_synthesizer.system', 'SYSTEM', '通用Agent-计划执行',
     '你是 LinkAgent 的 Plan-and-Execute Synthesizer。你的任务是基于用户请求、计划和工具观察结果生成最终回答。必须优先使用已执行结果，不要编造未观察到的数据。若计划有失败或跳过步骤，要明确说明影响，并给出用户还能继续推进的下一步。回答用中文，结构清晰，直接服务 B 站内容创作者或开发者当前问题。',
     'PaE Synthesizer：把计划执行结果合成为最终回答'),
    ('agent_multi_planner.system', 'SYSTEM', '通用Agent-多Agent',
     '你是 LinkAgent 的 Multi Agent Orchestrator Planner。你的任务是把用户请求拆成 Worker 调用计划。必须输出 JSON 对象，字段匹配 WorkerPlan：objective、calls、rationale、coverageCheck。calls 中每个调用包含 id、workerName、subTask、sharedContext、dependsOn。workerName 必须来自 Worker 清单，不允许编造 Worker。不要为了展示多 Agent 而强行拆分；能单 Worker 完成就只安排一个。最多 4 个 Worker 调用。Worker 清单：\n{workerList}',
     '多 Agent Planner：根据 Worker 能力生成调度计划'),
    ('agent_multi_direct_worker.system', 'SYSTEM', '通用Agent-多Agent',
     '你是 LinkAgent 的直接推理 Worker。你只处理不需要工具调用的子任务，例如解释、归纳、改写、结构化表达和创作建议。请严格围绕 Orchestrator 分配的子任务回答，不要越权处理其他 Worker 的职责。若上下文证据不足，请明确说明。',
     '多 Agent Direct Worker：处理不需要工具的语言推理子任务'),
    ('agent_multi_synthesizer.system', 'SYSTEM', '通用Agent-多Agent',
     '你是 LinkAgent 的 Multi Agent Synthesizer。你的任务是综合多个 Worker 的结果，生成给用户的最终回答。必须保留 Worker 已验证的事实，不要编造 Worker 没有给出的证据。若 Worker 之间有冲突，先指出冲突，再给出保守结论和下一步建议。回答用中文，优先给可执行建议。',
     '多 Agent Synthesizer：综合 Worker 结果生成最终回答')
ON DUPLICATE KEY UPDATE
    prompt_type = VALUES(prompt_type),
    scene = VALUES(scene),
    description = VALUES(description),
    is_deleted = 0;

-- ------------------------------------------------------------
-- 23. 运行期设置表（阶段 5.6）
--     只保存服务端白名单允许动态修改的开关覆盖值；没有覆盖值时后端回退 application.yml，避免初始化脚本误覆盖环境配置。
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS app_runtime_setting
(
    id            BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    setting_key   VARCHAR(128) NOT NULL COMMENT '设置键，只允许服务端白名单内的运行期开关',
    setting_value VARCHAR(64)  NOT NULL COMMENT '设置值，布尔开关统一保存 true 或 false',
    description   VARCHAR(255) NOT NULL COMMENT '中文说明，帮助后续维护者理解这个开关影响什么能力',
    create_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    is_deleted    TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0=正常，1=已删除',
    UNIQUE KEY uk_runtime_setting_key (setting_key)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = '运行期设置表';

-- ------------------------------------------------------------
-- 24. 模型 API 调用流水表（阶段 5.9）
--     记录文本 LLM、Embedding、Rerank 的真实调用开销，支撑任务级全链路追溯。
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS llm_api_call_log
(
    id                BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    call_id           VARCHAR(64)  NOT NULL COMMENT '单次模型 API 调用唯一标识，用于明细追踪',
    task_id           VARCHAR(64)           DEFAULT NULL COMMENT '关联创作任务 ID；通用 Agent 或后台能力没有任务时允许为空',
    trace_id          VARCHAR(64)           DEFAULT NULL COMMENT '一次业务链路的追踪 ID，用于把同一任务请求内多次模型调用串起来',
    request_id        VARCHAR(64)           DEFAULT NULL COMMENT '一次前端或后端请求 ID，用于排查同一 HTTP 请求产生的多次调用',
    workflow_session_id VARCHAR(64)         DEFAULT NULL COMMENT '工作流会话ID，用于把模型调用归属到一次创作者工作流',
    workflow_step_id  VARCHAR(64)           DEFAULT NULL COMMENT '工作流步骤ID，用于把模型调用归属到具体执行步骤',
    workflow_step_name VARCHAR(100)         DEFAULT NULL COMMENT '工作流步骤名称，用于前端展示模型调用发生在哪一步',
    workflow_stage    VARCHAR(32)           DEFAULT NULL COMMENT '工作流阶段，例如 PRE_PUBLISH、FEEDBACK、REPORT',
    model_category    VARCHAR(32)  NOT NULL COMMENT '模型分类：TEXT=文本大模型，EMBEDDING=向量化模型，RERANK=重排序模型',
    scene             VARCHAR(64)           DEFAULT NULL COMMENT '调用场景，例如发布前优化、反馈追问、知识库检索或向量索引',
    model_name        VARCHAR(128)          DEFAULT NULL COMMENT '模型名称；供应商未返回时允许为空',
    prompt_tokens     INT                   DEFAULT NULL COMMENT '输入 token 数；只有供应商返回精确 usage 时才填写',
    completion_tokens INT                   DEFAULT NULL COMMENT '输出 token 数；Embedding 和 Rerank 通常为空',
    total_tokens      INT                   DEFAULT NULL COMMENT '总 token 数；未知时保持为空，避免把未知误认为零消耗',
    elapsed_ms        BIGINT                DEFAULT NULL COMMENT '本次调用耗时毫秒；用于定位慢调用',
    status            VARCHAR(32)  NOT NULL COMMENT '调用状态：SUCCESS=成功，FAILED=失败，SKIPPED=因开关或候选不足跳过',
    error_message     VARCHAR(512)          DEFAULT NULL COMMENT '失败原因摘要，截断保存以避免异常堆栈撑爆表',
    input_count       INT                   DEFAULT NULL COMMENT '本次输入条数，例如 Embedding 文档数或 Rerank 候选文档数',
    create_time       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    is_deleted        TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0=正常，1=已删除',
    UNIQUE KEY uk_llm_api_call_id (call_id),
    KEY idx_llm_api_task_time (task_id, create_time),
    KEY idx_llm_api_task_category_time (task_id, model_category, create_time),
    KEY idx_llm_api_workflow_step (workflow_session_id, workflow_step_id, create_time),
    KEY idx_llm_api_trace_id (trace_id),
    KEY idx_llm_api_status_time (status, create_time)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = '模型 API 调用流水表';
