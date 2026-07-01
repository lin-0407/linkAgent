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
    step_index  INT         NOT NULL COMMENT '步骤序号，从 1 开始',
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
-- 7.1 交互式创作会话表
--     保存用户自然语言想法和 AI 创意方案状态，让创意卡片不混入普通任务材料
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS creator_interactive_session
(
    id                 BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    session_id         VARCHAR(64)  NOT NULL COMMENT '交互式创作会话唯一标识（UUID）',
    task_id            VARCHAR(64)  NOT NULL COMMENT '关联 creator_task.task_id，一次创意会话最终沉淀为一个创作任务',
    user_id            VARCHAR(64)  NOT NULL DEFAULT 'default' COMMENT '用户标识，第一版沿用默认用户便于本地演示',
    idea               TEXT         NOT NULL COMMENT '用户输入的原始创作想法，用于后续复盘 AI 是否偏离需求',
    video_type         VARCHAR(64)  NOT NULL DEFAULT '未分类' COMMENT '用户选择或系统兜底的视频类型，用于后续语境库检索',
    status             VARCHAR(32)  NOT NULL DEFAULT 'IDEA_INPUT' COMMENT '会话状态：IDEA_INPUT=等待输入，CREATIVE_GENERATING=生成中，CREATIVE_OPTIONS_READY=待选择，CREATIVE_CONFIRMED=已确认',
    selected_option_id VARCHAR(64)           DEFAULT NULL COMMENT '用户最终确认的创意卡片 ID，未确认时为空',
    raw_output         LONGTEXT              DEFAULT NULL COMMENT 'LLM 生成创意卡片的原始输出，用于失败回放和人工检查',
    parse_status       VARCHAR(32)  NOT NULL DEFAULT 'PENDING' COMMENT '解析状态：PENDING=未生成，PARSED=已解析，RAW_ONLY=仅保存原文并使用兜底卡片',
    background_context LONGTEXT              DEFAULT NULL COMMENT '用户上传的补充背景资料（从文档中提取的纯文本，可累积追加多个文件的内容）',
    understanding_summary TEXT                DEFAULT NULL COMMENT 'AI 对用户创作想法的理解摘要，用于用户在生成方向卡前核验 AI 是否准确理解了创作意图',
    understanding_status VARCHAR(32) NOT NULL DEFAULT 'NONE' COMMENT '理解确认状态：NONE=未开始，UNDERSTANDING=生成中，READY=待用户确认，CONFIRMED=用户已确认',
    create_time        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    is_deleted         TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0=正常，1=已删除',
    UNIQUE KEY uk_interactive_session_id (session_id),
    UNIQUE KEY uk_interactive_task_id (task_id),
    KEY idx_interactive_user_update_time (user_id, update_time),
    KEY idx_interactive_status_update_time (status, update_time)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = '交互式创作会话表';

-- ------------------------------------------------------------
-- 7.2 创意卡片表
--     保存 AI 为一个创作想法生成的候选方向，用户确认后回写到标准任务材料
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS creator_idea_option
(
    id                  BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    option_id           VARCHAR(64)  NOT NULL COMMENT '创意卡片唯一标识（UUID）',
    session_id          VARCHAR(64)  NOT NULL COMMENT '关联 creator_interactive_session.session_id',
    task_id             VARCHAR(64)  NOT NULL COMMENT '关联 creator_task.task_id，便于按任务追溯候选方案',
    option_name         VARCHAR(128) NOT NULL COMMENT '创意名称，一句话概括该方向',
    target_audience     TEXT                  DEFAULT NULL COMMENT '适合人群，说明该方向面向的观众',
    title_outline       TEXT                  DEFAULT NULL COMMENT '标题大纲 JSON 数组，保存标题表达方向而非单个最终标题',
    content_outline     TEXT                  DEFAULT NULL COMMENT '内容大纲 JSON 数组，保存开头、主体和结尾结构',
    description_outline TEXT                  DEFAULT NULL COMMENT '简介大纲 JSON 数组，保存 B 站简介卖点、关键词和引导语',
    selling_points      TEXT                  DEFAULT NULL COMMENT '亮点 JSON 数组，说明该方向为什么贴合用户想法',
    risk_points         TEXT                  DEFAULT NULL COMMENT '风险 JSON 数组，说明可能跑偏、过度承诺或误解的地方',
    recommend_reason    TEXT                  DEFAULT NULL COMMENT 'AI 推荐理由，用于帮助用户在三张卡片中做选择',
    selected            TINYINT      NOT NULL DEFAULT 0 COMMENT '是否被用户选择：0=未选择，1=已选择',
    create_time         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    is_deleted          TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0=正常，1=已删除',
    UNIQUE KEY uk_idea_option_id (option_id),
    KEY idx_idea_session_id (session_id),
    KEY idx_idea_task_id (task_id),
    KEY idx_idea_selected (session_id, selected)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = '创意卡片表';

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
    evidence_refs          JSON                  DEFAULT NULL COMMENT '发布前优化可引用证据 JSON，记录任务材料、创作者偏好、类型语境和同类案例等依据',
    missing_info           JSON                  DEFAULT NULL COMMENT '缺失信息 JSON，记录会影响建议准确性但当前没有提供的信息',
    generation_mode        VARCHAR(64)           DEFAULT NULL COMMENT '生成模式：DIRECT_LLM_EVIDENCE=直连模型证据化，AGENT_RAG_EVIDENCE=Agent证据化',
    quality_status         VARCHAR(64)           DEFAULT NULL COMMENT '质量状态：AUDIT_PASSED=审查通过，AUDIT_WARNED=存在警告，AUDIT_FAILED=存在错误，AUDIT_SKIPPED=未审查',
    audit_report           JSON                  DEFAULT NULL COMMENT '发布前优化建议审查报告 JSON，记录证据引用、夸大承诺和结构完整性检查结果',
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
    view_count            BIGINT UNSIGNED          DEFAULT NULL COMMENT '播放量，质量打分的分母；缺失或为 0 时不打分（raw_quality_score 和 quality_score 置空）',
    like_count            BIGINT UNSIGNED          DEFAULT NULL COMMENT '点赞量，质量打分互动率分子之一；离线脚本未取到时为空',
    coin_count            BIGINT UNSIGNED          DEFAULT NULL COMMENT '投币量，质量信号最强、打分权重最高；缺失为空',
    favorite_count        BIGINT UNSIGNED          DEFAULT NULL COMMENT '收藏量，质量打分互动率分子之一；缺失为空',
    danmaku_count         BIGINT UNSIGNED          DEFAULT NULL COMMENT '弹幕量，质量打分互动率分子之一；缺失为空',
    reply_count           BIGINT UNSIGNED          DEFAULT NULL COMMENT '评论量，质量打分互动率分子之一；缺失为空',
    highlight_summary     LONGTEXT                 DEFAULT NULL COMMENT '清洗后优质评论 / 弹幕的亮点摘要，由小模型汇总，作为案例卡片语义主体之一（5.1c 生成，5.1a 为空）',
    raw_quality_score     DECIMAL(12, 6)           DEFAULT NULL COMMENT '单视频独立原始质量分，由互动率和情绪因子直接计算；不依赖同分区其它视频，用于小样本兜底排序',
    quality_score         DECIMAL(6, 2)            DEFAULT NULL COMMENT '分区归一化相对质量分（0–100）；同分区有效样本不足或原始分无差异时为空，避免少样本展示 60/0/100 误导用户',
    quality_sample_count  INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '本次分区归一化可参与打分的有效视频数；用于判断 quality_score 是否具备展示可信度',
    quality_score_reliable TINYINT        NOT NULL DEFAULT 0 COMMENT '质量分是否达到展示可信度：1=可展示相对质量分，0=仅保留原始分作内部排序或样本不足',
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
    ('agent_plan_execute_replanner.system', 'SYSTEM', '通用Agent-计划执行',
     '你是 LinkAgent 的 Plan-and-Execute Replanner。你的任务是基于用户请求、已执行步骤、剩余步骤和失败方案指纹，重新规划尚未执行的步骤。必须输出 JSON 对象，字段匹配 AgentPlan：objective、steps、rationale、coverageCheck。steps 只包含后续需要执行的新步骤，不要重复已经成功的步骤；action 必须来自工具清单；不要再次使用失败方案指纹中的 action + actionInput；如果剩余诉求无法继续满足，返回空 steps，并在 coverageCheck 说明原因。可用工具：\n{toolList}',
     'PaE Replanner：根据执行结果重规划剩余步骤'),
    ('agent_plan_execute_synthesizer.system', 'SYSTEM', '通用Agent-计划执行',
     '你是 LinkAgent 的 Plan-and-Execute Synthesizer。你的任务是基于用户请求、计划执行结果和可用证据生成最终回答。必须输出 JSON 对象，字段匹配 CitedAnswer：statements、limitations。statements 中每条包含 text 和 evidenceIds；每个事实性陈述都必须引用 evidenceIds，不要编造未观察到的数据。若证据不足，请在 limitations 说明，宁可说没有依据，也不要硬编。若计划有失败或跳过步骤，要明确说明影响，并给出用户还能继续推进的下一步。回答用中文，结构清晰，直接服务 B 站内容创作者或开发者当前问题。',
     'PaE Synthesizer：把计划执行结果合成为最终回答'),
    ('agent_multi_planner.system', 'SYSTEM', '通用Agent-多Agent',
     '你是 LinkAgent 的 Multi Agent Orchestrator Planner。你的任务是把用户请求拆成 Worker 调用计划。必须输出 JSON 对象，字段匹配 WorkerPlan：objective、calls、rationale、coverageCheck。calls 中每个调用包含 id、workerName、subTask、sharedContext、dependsOn。workerName 必须来自 Worker 清单，不允许编造 Worker。不要为了展示多 Agent 而强行拆分；能单 Worker 完成就只安排一个。最多 4 个 Worker 调用。Worker 清单：\n{workerList}',
     '多 Agent Planner：根据 Worker 能力生成调度计划'),
    ('agent_multi_direct_worker.system', 'SYSTEM', '通用Agent-多Agent',
     '你是 LinkAgent 的直接推理 Worker。你只处理不需要工具调用的子任务，例如解释、归纳、改写、结构化表达和创作建议。请严格围绕 Orchestrator 分配的子任务回答，不要越权处理其他 Worker 的职责。若上下文证据不足，请明确说明。',
     '多 Agent Direct Worker：处理不需要工具的语言推理子任务'),
    ('agent_multi_synthesizer.system', 'SYSTEM', '通用Agent-多Agent',
     '你是 LinkAgent 的 Multi Agent Synthesizer。你的任务是综合多个 Worker 的结构化摘要和证据，生成给用户的最终回答。必须输出 JSON 对象，字段匹配 CitedAnswer：statements、limitations。statements 中每条包含 text 和 evidenceIds；每个事实性陈述都必须引用 evidenceIds，不要编造 Worker 没有给出的证据。Worker 推理类证据只能支持建议或保守判断，不能当作外部事实。若 Worker 之间有冲突，先指出冲突，再给出保守结论和下一步建议。回答用中文，优先给可执行建议。',
     '多 Agent Synthesizer：综合 Worker 结果生成最终回答'),
    ('agent_answer_auditor.system', 'SYSTEM', '通用Agent-答案审查',
     '你是 LinkAgent 的最终答案审查器。你的任务是审查候选回答是否回答完用户问题、是否自相矛盾、是否存在没有证据 id 的事实性断言、是否把 Worker 推理当成外部事实。必须输出 JSON 对象，字段匹配 AnswerAuditReport：passed、overallComment、issues、rewriteInstructions。issues 中每项包含 issueType、description、relatedEvidenceIds。只有当回答完整、保守且每个事实性陈述都有有效证据时，passed 才能为 true。',
     'Agent 答案审查器：检查最终回答完整性、矛盾和引用缺失')
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

-- ------------------------------------------------------------
-- 25. 创作者事件流水表
--     记录用户对 AI 建议的每一次采纳/拒绝/修改等业务动作，
--     作为创作者画像增量更新的信号源（方案一：创作者记忆系统）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS creator_event
(
    id          BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    event_id    VARCHAR(64)  NOT NULL COMMENT '事件唯一标识（UUID）',
    creator_id  VARCHAR(64)  NOT NULL COMMENT '用户标识，关联 creator_profile.creator_id',
    event_type  VARCHAR(32)  NOT NULL COMMENT '事件类型：TITLE_ACCEPTED / TITLE_REJECTED / TAG_ACCEPTED / TAG_REJECTED / FEEDBACK_INSIGHT_SAVED / SUGGESTION_ADOPTED / SUGGESTION_REJECTED',
    task_id     VARCHAR(64)           DEFAULT NULL COMMENT '关联 creator_task.task_id，用于追溯事件发生的创作上下文',
    payload     JSON                  DEFAULT NULL COMMENT '事件详情 JSON，如 { "title": "...", "reason": "风格不符合" }',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '事件发生时间',
    is_deleted  TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0=正常，1=已删除',
    UNIQUE KEY uk_event_id (event_id),
    KEY idx_creator_time (creator_id, created_at),
    KEY idx_creator_type_time (creator_id, event_type, created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = '创作者事件流水表';

-- ------------------------------------------------------------
-- 26. 创作者画像表
--     用户级聚合画像，从 creator_event 和 creator_preference 定期推理生成，
--     跨任务汇总用户的风格、语气偏好和受众认知（方案一：创作者记忆系统）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS creator_profile
(
    id            BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    creator_id    VARCHAR(64)  NOT NULL COMMENT '用户标识，与 creator_preference.user_id 对应',
    style_tags    JSON                  DEFAULT NULL COMMENT '风格标签 JSON 数组，如 ["理性分析型", "冷幽默", "数据驱动"]',
    tone_guide    TEXT                  DEFAULT NULL COMMENT '语气指南，描述标题句式偏好、排斥的表达方式、标签数量倾向等',
    audience_view TEXT                  DEFAULT NULL COMMENT '受众认知，描述核心观众画像和内容偏好',
    create_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '画像最后更新时间',
    is_deleted    TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0=正常，1=已删除',
    UNIQUE KEY uk_creator_id (creator_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = '创作者画像表';

-- 创作者画像种子提示词：初始画像生成和增量更新
INSERT INTO llm_prompt_template (prompt_key, prompt_type, scene, content, description)
VALUES
    ('creator_profile.init.system', 'SYSTEM', '创作者画像',
     '你是 B 站创作者画像分析助手。你的任务是根据用户的历史创作偏好记录，提炼出用户的核心创作特征。必须输出 JSON 对象，字段：styleTags（字符串数组，3-5个风格标签，如"理性分析型""冷幽默""数据驱动"）、toneGuide（语气偏好描述，包括标题句式偏好、排斥的表达方式、标签数量倾向等，200字以内）、audienceView（受众认知描述，包括核心观众画像和内容偏好，200字以内）。不要编造不存在的信息，如果某方面证据不足，用"尚不明确"标注。',
     '创作者画像初始化：从历史偏好生成初始画像'),
    ('creator_profile.init.user', 'USER', '创作者画像',
     '以下是该创作者的历史偏好记录（来自多期创作复盘），请提炼出创作者的风格特征、语气偏好和受众认知：\n\n{preferenceSummary}',
     '创作者画像初始化用户提示词，preferenceSummary 为历史偏好汇总文本'),
    ('creator_profile.update.system', 'SYSTEM', '创作者画像',
     '你是 B 站创作者画像分析助手。你的任务是根据用户最近的操作事件，增量更新用户的创作画像。必须输出 JSON 对象，字段与初始化相同：styleTags、toneGuide、audienceView。更新原则：1）确认的趋势（如连续3次拒绝某类标题）要明确写进画像；2）新的偏好变化要更新对应字段；3）旧的仍适用的特征保留，不要因为没出现在最近事件中就删除；4）不确定的变化用"可能倾向于""近期出现"等保守表述。',
     '创作者画像增量更新：根据最近事件调整画像'),
    ('creator_profile.update.user', 'USER', '创作者画像',
     '当前画像：\n{currentProfile}\n\n最近创作事件（按时间倒序）：\n{recentEvents}\n\n请基于这些事件增量更新画像。只修改需要调整的部分，保留仍然适用的旧特征。',
     '创作者画像增量更新用户提示词，currentProfile 为当前画像文本，recentEvents 为最近事件列表')
ON DUPLICATE KEY UPDATE
    prompt_type = VALUES(prompt_type),
    scene = VALUES(scene),
    description = VALUES(description),
    is_deleted = 0;

-- 字段自动补全提示词：前端输入框 AI 按钮用，调轻量模型
INSERT INTO llm_prompt_template (prompt_key, prompt_type, scene, content, description)
VALUES
    ('field_autofill.system', 'SYSTEM', '字段自动补全',
     '你是 B 站 UP 主创作助手，专门帮助补全创作任务中的表单字段。根据任务全局上下文（材料、画像、偏好、已有分析等），为指定字段生成简洁、可用的补全建议。要求：1）只输出建议文本，不要输出解释、理由或分析过程；2）建议要贴合上下文而非泛泛而谈；3）标题草稿输出1-2个备选标题，用换行分隔；4）其他字段输出一段连贯文本；5）不要照搬上下文中的已有内容，而是结合上下文生成新内容。',
     '字段自动补全系统提示词'),
    ('field_autofill.user', 'USER', '字段自动补全',
     '任务：{taskName}\n视频类型：{videoType}\n待补全字段：{fieldType}\n\n任务全局上下文：\n{globalContext}\n\n请为【{fieldType}】生成补全建议。',
     '字段自动补全用户提示词，fieldType=字段中文名，globalContext=任务上下文')
ON DUPLICATE KEY UPDATE
    prompt_type = VALUES(prompt_type),
    scene = VALUES(scene),
    description = VALUES(description),
    is_deleted = 0;

-- Agent Executor 提示词（ReAct 模式 + 结构化输出模式）
INSERT INTO llm_prompt_template (prompt_key, prompt_type, scene, content, description)
VALUES
    ('agent_executor.system', 'SYSTEM', '通用Agent-执行器',
     '你是 LinkAgent 的 Agent Executor。你的任务是基于当前计划步骤和可用工具，逐步执行并观察结果。请遵循 ReAct 模式：先思考(Thought)当前步骤要完成什么，再决定调用哪个工具(Action)和参数(Action Input)，等待观察结果(Observation)后进入下一步。执行真实外部操作前要先用文本描述操作意图和前置条件，避免用文本模拟工具调用。如果计划中某步骤不需要工具，或者工具执行后证据充分，直接输出最终回答。可用工具：\n{toolList}',
     'Agent Executor：ReAct 模式执行工具调用'),
    ('agent_executor_structured.system', 'SYSTEM', '通用Agent-结构化执行器',
     '你是 LinkAgent 的 Agent Executor（结构化输出模式）。你的任务是基于当前计划步骤和可用工具，逐步执行并返回结构化结果。每一步必须输出 JSON 对象：如果本步需要调用工具，返回 {"action": "工具名", "actionInput": {"参数名": "参数值"}}；如果本步不需要工具或已完成推理，返回 {"finalAnswer": "最终回答文本"}。不要在 JSON 之外输出额外解释文本。工具参数必须完整填写，不要使用占位符或缩略写法。可用工具：\n{toolList}',
     'Agent Executor 结构化输出模式（阶段5.4）：每步输出 JSON，由 BeanOutputConverter 自动校验')
ON DUPLICATE KEY UPDATE
    prompt_type = VALUES(prompt_type),
    scene = VALUES(scene),
    description = VALUES(description),
    is_deleted = 0;

-- ============================================================
-- Agent 周边能力提示词：记忆、知识库、竞品、反馈、报告
-- ============================================================
INSERT INTO llm_prompt_template (prompt_key, prompt_type, scene, content, description)
VALUES
    -- 长期记忆抽取（阶段 5.3）
    ('long_term_memory.system', 'SYSTEM', '记忆-长期记忆',
     '你是 LinkAgent 的记忆提取助手。你的任务是从用户消息和 Agent 回答中提取值得长期保留的信息，包括但不限于：用户偏好、创作风格倾向、对特定内容的明确态度（喜欢/排斥）、习惯性表述方式、以及未来可能有用的上下文事实。必须输出 JSON 对象，字段：{memory（记忆文本，一段简洁陈述，不要重复对话原文）、importance（重要性评分 1-3：1=可选记，2=有意义，3=关键认知，缺失则为2）、topic（记忆主题，如"标题风格""标签偏好""内容类型""发布习惯"）、confidence（置信度 0-1：1=明确表达，0.5=推测，缺失则为1）}。不要编造用户没有表达的内容。',
     '长期记忆抽取系统提示词：从对话中提取用户偏好和关键事实'),
    ('long_term_memory.user', 'USER', '记忆-长期记忆',
     '用户消息：{userMessage}\n\nAgent 最终回答：{finalAnswer}\n\n请从以上对话中提取值得长期保留的记忆信息。',
     '长期记忆抽取用户提示词：userMessage 为用户输入，finalAnswer 为 Agent 回答'),

    -- 对话摘要记忆（阶段 5.3）
    ('summary_memory.system', 'SYSTEM', '记忆-摘要记忆',
     '你是 LinkAgent 的对话摘要助手。你的任务是将一段对话历史压缩为简洁的摘要，保留关键决策、用户偏好变更和待跟进事项。摘要限定在 300 字以内，优先保留对后续对话有参考价值的信息，省略纯工具调用过程和重复确认。输出纯文本，不要套 JSON 或 Markdown 代码块。',
     '对话摘要记忆系统提示词：压缩对话历史保留关键信息'),

    -- HyDE 假设文档生成（知识库 RAG）
    ('hyde.system', 'SYSTEM', '知识库-HyDE查询',
     '你是一个视频创作知识检索助手。根据用户的查询需求，生成一篇"假设的理想参考文档"，这篇文档应该包含用户想找的知识点或案例特征。生成的内容应该像一篇真实存在的创作指南或案例分析，包含具体细节、技巧描述和适用场景。长度控制在 200 字以内，直接输出文档正文，不要加"以下是假设文档"之类的前缀。',
     'HyDE 假设文档生成：将查询转换为假想参考文档以提升向量检索召回'),

    -- 参考案例清洗摘要
    ('reference_cleaning.system', 'SYSTEM', '知识库-案例清洗',
     '你是 B 站创作案例分析师。你的任务是基于一组参考视频的正负向亮点条目，提炼出该参考视频的核心可借鉴点。输出一段 200 字以内的中文摘要，只保留对有创作参考价值的观察（例如标题策略、叙事手法、剪辑节奏、互动技巧等），忽略纯数据指标和不具体的夸奖。不要输出 JSON，直接输出摘要文本。',
     '参考案例清洗：从亮点条目提炼创作可借鉴摘要'),

    -- 竞品分析
    ('competitor.system', 'SYSTEM', '创作者-竞品分析',
     '你是 B 站内容竞品分析专家。你的任务是将创作者的视频与竞品视频进行对比分析，找出差异点、可借鉴策略和创作者自身的优势。分析维度包括但不限于：标题策略、标签覆盖、叙事结构、情感节奏、受众定位和传播潜力。输出用中文，结构清晰，每个分析点都要有具体的对比例子，不要空泛评价。优先给出创作者可直接执行的改进建议。',
     '竞品分析系统提示词：对比竞品视频并给出可执行建议'),
    ('competitor.user', 'USER', '创作者-竞品分析',
     '任务：{taskName}（ID: {taskId}）\n\n自定义指导：{customGuidance}\n分析重点：{analysisFocus}\n额外要求：{extraRequirement}\n\n竞品视频：{competitorVideoName}（BV: {competitorBvId}）\n分类：{category}\n对比维度：{compareDimension}\n补充上下文：{extraContext}\n\n创作者素材：\n{materials}\n发布前建议：{suggestionResult}\n反馈分析结果：{feedbackResult}',
     '竞品分析用户提示词：包含任务信息、竞品信息、素材和建议上下文'),

    -- 反馈分析
    ('feedback_analyze.system', 'SYSTEM', '创作者-反馈分析',
     '你是 B 站观众反馈分析专家。你的任务是分析视频的观众评论和弹幕数据，提炼出：1）观众整体情绪倾向（正面/负面/中性占比）；2）高频话题和关键词；3）关键建议和批评（按优先级排序）；4）内容改进方向。分析要基于真实数据样本，不要凭空推测。对于样本量不足的情况，要明确标注"样本有限，结论置信度较低"。输出结构清晰的 Markdown 报告。',
     '反馈分析系统提示词：分析观众评论和弹幕数据并产出报告'),
    ('feedback_analyze.user', 'USER', '创作者-反馈分析',
     '任务：{taskName}（ID: {taskId}）\n\n自定义指导：{customGuidance}\n分析重点：{analysisFocus}\n额外要求：{extraRequirement}\n补充上下文：{extraContext}\n\n评论样本：\n{commentSamples}\n\n弹幕样本：\n{danmakuSamples}',
     '反馈分析用户提示词：包含评论和弹幕样本数据'),

    -- 反馈追问
    ('feedback_chat.system', 'SYSTEM', '创作者-反馈追问',
     '你是 B 站创作数据解读助手。你的任务是基于已有的反馈分析报告和明细数据，回答创作者关于观众反馈的具体追问。回答要引用报告中的具体发现和证据条目，不要脱离数据空谈。如果问题超出已有数据范围，诚实说明"当前数据不足以回答这个问题"，并建议创作者补充哪种数据。回答用中文，简洁直接，优先给可执行建议。',
     '反馈追问系统提示词：基于报告数据回答创作者追问'),
    ('feedback_chat.user', 'USER', '创作者-反馈追问',
     '任务：{taskName}（ID: {taskId}）\n\n创作者提问：{question}\n\n报告上下文：\n{reportContext}\n\n证据明细：\n{evidenceContext}',
     '反馈追问用户提示词：包含报告上下文和证据明细'),

    -- 发布前优化建议
    ('pre_publish.system', 'SYSTEM', '创作者-发布前优化',
     '你是 B 站内容创作优化顾问。你的任务是在创作者发布视频前，基于任务素材和创作者偏好，给出全面的发布优化建议。覆盖维度包括：1）标题优化（吸引力、SEO、关键词密度）；2）标签策略（核心标签、长尾标签、话题标签）；3）发布时机（基于内容类型的建议发布时间段）；4）封面和简介优化建议；5）风险提示（可能引起争议的内容点）。输出结构清晰的建议，每项建议都要说明理由。优先尊重创作者的风格偏好，不要强行套用通用模板。',
     '发布前优化系统提示词：基于任务素材和偏好给出发布建议'),
    ('pre_publish.user', 'USER', '创作者-发布前优化',
     '任务：{taskName}（ID: {taskId}）\n\n自定义指导：{customGuidance}\n偏好使用方式：{preferenceMode}\n\n创作素材：\n{materials}\n\n创作者偏好上下文：\n{preferenceContext}\n\n分析策略：{strategyContext}',
     '发布前优化用户提示词：包含素材、偏好和策略上下文'),

    -- 创作复盘报告
    ('report.system', 'SYSTEM', '创作者-复盘报告',
     '你是 B 站创作者复盘分析专家。你的任务是基于创作者提供的素材、发布前建议、观众反馈和竞品分析结果，生成一期完整的创作复盘报告。报告结构：1）本期概览（核心数据和关键发现）；2）内容亮点（做到了什么、哪些策略有效）；3）改进空间（对比建议和实际表现的差距）；4）观众洞察（从反馈中提炼的受众认知变化）；5）下期行动清单（3-5 条具体可执行的改进项）。如果某个维度数据不足，明确说明而非编造。输出 Markdown 格式，用中文。',
     '复盘报告系统提示词：综合各阶段数据生成完整复盘'),
    ('report.user', 'USER', '创作者-复盘报告',
     '任务：{taskName}（ID: {taskId}）\n\n自定义指导：{customGuidance}\n复盘重点：{reviewFocus}\n额外要求：{extraRequirement}\n\n创作素材：\n{materials}\n\n发布前建议结果：\n{suggestionResult}\n\n观众反馈结果：\n{feedbackResult}\n\n竞品分析结果：\n{competitorResult}\n\n跨期上下文：{crossPeriodContext}',
     '复盘报告用户提示词：包含全链路分析结果和跨期上下文')
ON DUPLICATE KEY UPDATE
    prompt_type = VALUES(prompt_type),
    scene = VALUES(scene),
    description = VALUES(description),
    is_deleted = 0;

-- ------------------------------------------------------------
-- 27. B站账号绑定表
--     保存用户绑定的 B 站 UID 和同步状态，作为视频缓存和任务视频绑定的前置依赖
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS creator_bilibili_account
(
    id              BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    account_id      VARCHAR(64)  NOT NULL COMMENT '平台内账号绑定唯一标识（UUID）',
    user_id         VARCHAR(64)  NOT NULL DEFAULT 'default' COMMENT '平台用户标识，与 creator_task.user_id 对应',
    bilibili_uid    VARCHAR(32)  NOT NULL COMMENT '用户填写的 B 站 UID，用于同步公开视频列表和校验 BV 归属',
    nickname        VARCHAR(128)          DEFAULT NULL COMMENT '同步到的 B 站昵称，取不到则为空',
    bind_status     VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE' COMMENT '绑定状态：ACTIVE=正常绑定，UNVERIFIED=UID未校验，SYNC_FAILED=同步失败',
    last_sync_time  DATETIME              DEFAULT NULL COMMENT '最近一次同步视频列表时间',
    last_sync_error VARCHAR(500)          DEFAULT NULL COMMENT '最近一次同步失败原因摘要，截断保存避免异常堆栈撑爆表',
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    is_deleted      TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0=正常，1=已删除',
    UNIQUE KEY uk_bilibili_account_id (account_id),
    UNIQUE KEY uk_bilibili_user_id (user_id),
    KEY idx_bilibili_uid (bilibili_uid)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = 'B站账号绑定表';

-- ------------------------------------------------------------
-- 28. B站视频缓存表
--     缓存从 B 站接口同步到的公开视频信息，作为任务视频绑定的数据源和自动采集的触发依据
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS creator_bilibili_video
(
    id              BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    video_id        VARCHAR(64)  NOT NULL COMMENT '平台内视频缓存唯一标识（UUID）',
    bilibili_uid    VARCHAR(32)  NOT NULL COMMENT '归属 B 站 UID，用于关联账号和校验 BV 归属',
    bvid            VARCHAR(20)  NOT NULL COMMENT 'B 站 BV 号，用于任务关联和排错',
    aid             BIGINT UNSIGNED       DEFAULT NULL COMMENT 'B 站 AV 号，取不到则为空',
    title           VARCHAR(255)          DEFAULT NULL COMMENT '视频标题，来自 B 站公开信息',
    cover_url       VARCHAR(500)          DEFAULT NULL COMMENT '视频封面 URL，来自 B 站公开信息',
    publish_time    DATETIME              DEFAULT NULL COMMENT 'B 站发布时间，用于判断评论弹幕成熟度',
    view_count      BIGINT UNSIGNED       DEFAULT NULL COMMENT '播放量，取不到为空',
    like_count      BIGINT UNSIGNED       DEFAULT NULL COMMENT '点赞量，取不到为空',
    coin_count      BIGINT UNSIGNED       DEFAULT NULL COMMENT '投币量，取不到为空',
    favorite_count  BIGINT UNSIGNED       DEFAULT NULL COMMENT '收藏量，取不到为空',
    share_count     BIGINT UNSIGNED       DEFAULT NULL COMMENT '分享量，取不到为空',
    sync_status     VARCHAR(32)  NOT NULL DEFAULT 'SYNCED' COMMENT '同步状态：SYNCED=已同步，STALE=数据过期，FAILED=同步失败',
    last_sync_time  DATETIME              DEFAULT NULL COMMENT '最近同步时间',
    raw_snapshot    JSON                  DEFAULT NULL COMMENT '原始同步快照 JSON，用于排查字段缺失和后续数据修复',
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    is_deleted      TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0=正常，1=已删除',
    UNIQUE KEY uk_bilibili_video_id (video_id),
    UNIQUE KEY uk_bilibili_bv_uid (bvid, bilibili_uid),
    KEY idx_bilibili_uid (bilibili_uid),
    KEY idx_bilibili_bvid (bvid)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = 'B站视频缓存表';

-- ------------------------------------------------------------
-- 29. 任务视频绑定表
--     把创作任务和具体的 B 站 BV 号关联起来，支持校验 BV 归属后触发自动视频分析
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS creator_task_video_binding
(
    id              BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    binding_id      VARCHAR(64)  NOT NULL COMMENT '绑定唯一标识（UUID）',
    task_id         VARCHAR(64)  NOT NULL COMMENT '关联 creator_task.task_id，每个任务第一版只绑定一个 BV',
    user_id         VARCHAR(64)  NOT NULL DEFAULT 'default' COMMENT '平台用户标识',
    bilibili_uid    VARCHAR(32)           DEFAULT NULL COMMENT '绑定时使用的 B 站 UID，用于后续校验 BV 是否属于该创作者',
    bvid            VARCHAR(20)  NOT NULL COMMENT '任务绑定的 BV 号，用于视频分析页筛选和自动采集触发',
    binding_status  VARCHAR(32)  NOT NULL DEFAULT 'WAITING_VERIFY' COMMENT '绑定状态：WAITING_VERIFY=等待校验，BOUND=已绑定校验通过，UID_MISMATCH=BV不属于该UID，VIDEO_NOT_FOUND=BV查不到视频',
    verify_message  VARCHAR(500)          DEFAULT NULL COMMENT '校验说明，用于前端展示绑定异常原因给用户',
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    is_deleted      TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0=正常，1=已删除',
    UNIQUE KEY uk_task_video_binding_id (binding_id),
    UNIQUE KEY uk_task_video_binding_task (task_id),
    KEY idx_binding_bvid (bvid),
    KEY idx_binding_status (binding_status)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = '任务视频绑定表';

-- ------------------------------------------------------------
-- 30. 视频分析报告表
--     保存 LLM 对已绑定视频的完整分析结果，涵盖发布方案兑现、观众关注点、误解争议和下一期行动清单
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS creator_video_analysis_report
(
    id                      BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    analysis_id             VARCHAR(64)  NOT NULL COMMENT '视频分析唯一标识（UUID）',
    task_id                 VARCHAR(64)  NOT NULL COMMENT '关联 creator_task.task_id',
    bvid                    VARCHAR(20)  NOT NULL COMMENT '分析的视频 BV 号',
    workflow_session_id     VARCHAR(64)           DEFAULT NULL COMMENT '关联工作流会话 ID，用于失败回放和步骤级开销追溯',
    analysis_status         VARCHAR(32)  NOT NULL DEFAULT 'PENDING' COMMENT '分析状态：PENDING=待分析，SYNCING=同步视频信息中，FETCHING=采集评论弹幕中，ANALYZING=LLM分析中，COMPLETED=已完成，FAILED=失败',
    one_sentence_summary    VARCHAR(200)          DEFAULT NULL COMMENT '一句话复盘，不超过80字，说明这条视频当前最重要的结论',
    publish_plan_review     TEXT                  DEFAULT NULL COMMENT '发布方案兑现情况 JSON，对比第二阶段确认方案说明哪些做到哪些偏离',
    audience_focus          TEXT                  DEFAULT NULL COMMENT '观众关注点 JSON 数组，总结评论弹幕高频观点和代表证据',
    misunderstanding_points TEXT                  DEFAULT NULL COMMENT '误解点 JSON 数组，说明观众哪里没看懂及可能原因',
    controversy_points      TEXT                  DEFAULT NULL COMMENT '争议点 JSON 数组，含风险等级和回应建议',
    next_action_plan        TEXT                  DEFAULT NULL COMMENT '下一期行动清单 JSON，包含标题、内容结构、互动和选题建议',
    evidence_summary        TEXT                  DEFAULT NULL COMMENT '证据摘要 JSON，可展开查看评论弹幕时间点和分类理由',
    raw_output              LONGTEXT              DEFAULT NULL COMMENT 'LLM 原始输出，用于失败回放和人工检查',
    parse_status            VARCHAR(32)  NOT NULL DEFAULT 'PENDING' COMMENT '解析状态：PENDING=未生成，PARSED=已解析，RAW_ONLY=仅保存原文',
    create_time             DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time             DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    is_deleted              TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0=正常，1=已删除',
    UNIQUE KEY uk_video_analysis_id (analysis_id),
    UNIQUE KEY uk_video_analysis_task (task_id),
    KEY idx_analysis_bvid (bvid),
    KEY idx_analysis_status (analysis_status)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = '视频分析报告表';

-- ============================================================
-- 幂等迁移：为已存在的表补充新列
-- 用 INFORMATION_SCHEMA 判断列是否存在，兼容所有 MySQL 8.x 版本
-- ============================================================

-- 参考视频质量分可信度字段：这里仅补齐旧库缺失列，避免 db-init 每次执行时清空已有质量分数据
SET @sql = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE creator_reference_video ADD COLUMN raw_quality_score DECIMAL(12, 6) DEFAULT NULL COMMENT ''单视频独立原始质量分，由互动率和情绪因子直接计算；不依赖同分区其它视频，用于小样本兜底排序'' AFTER highlight_summary',
    'SELECT 1 AS ok'
) FROM INFORMATION_SCHEMA.COLUMNS
WHERE TABLE_SCHEMA = 'link_agent' AND TABLE_NAME = 'creator_reference_video' AND COLUMN_NAME = 'raw_quality_score');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE creator_reference_video ADD COLUMN quality_sample_count INT UNSIGNED NOT NULL DEFAULT 0 COMMENT ''本次分区归一化可参与打分的有效视频数；用于判断 quality_score 是否具备展示可信度'' AFTER quality_score',
    'SELECT 1 AS ok'
) FROM INFORMATION_SCHEMA.COLUMNS
WHERE TABLE_SCHEMA = 'link_agent' AND TABLE_NAME = 'creator_reference_video' AND COLUMN_NAME = 'quality_sample_count');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE creator_reference_video ADD COLUMN quality_score_reliable TINYINT NOT NULL DEFAULT 0 COMMENT ''质量分是否达到展示可信度：1=可展示相对质量分，0=仅保留原始分作内部排序或样本不足'' AFTER quality_sample_count',
    'SELECT 1 AS ok'
) FROM INFORMATION_SCHEMA.COLUMNS
WHERE TABLE_SCHEMA = 'link_agent' AND TABLE_NAME = 'creator_reference_video' AND COLUMN_NAME = 'quality_score_reliable');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 阶段6.3：交互式创作会话新增补充背景文档和AI理解确认字段
SET @sql = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE creator_interactive_session ADD COLUMN background_context LONGTEXT DEFAULT NULL COMMENT ''用户上传的补充背景资料（从文档中提取的纯文本，可累积追加多个文件的内容）''',
    'SELECT 1 AS ok'
) FROM INFORMATION_SCHEMA.COLUMNS
WHERE TABLE_SCHEMA = 'link_agent' AND TABLE_NAME = 'creator_interactive_session' AND COLUMN_NAME = 'background_context');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE creator_interactive_session ADD COLUMN understanding_summary TEXT DEFAULT NULL COMMENT ''AI 对用户创作想法的理解摘要，用于用户在生成方向卡前核验 AI 是否准确理解了创作意图''',
    'SELECT 1 AS ok'
) FROM INFORMATION_SCHEMA.COLUMNS
WHERE TABLE_SCHEMA = 'link_agent' AND TABLE_NAME = 'creator_interactive_session' AND COLUMN_NAME = 'understanding_summary');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE creator_interactive_session ADD COLUMN understanding_status VARCHAR(32) NOT NULL DEFAULT ''NONE'' COMMENT ''理解确认状态：NONE=未开始，UNDERSTANDING=生成中，READY=待用户确认，CONFIRMED=用户已确认''',
    'SELECT 1 AS ok'
) FROM INFORMATION_SCHEMA.COLUMNS
WHERE TABLE_SCHEMA = 'link_agent' AND TABLE_NAME = 'creator_interactive_session' AND COLUMN_NAME = 'understanding_status');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 发布前优化证据化字段兼容补丁：已有本地库执行 init.sql 时自动补齐，不引入迁移框架。
SET @sql = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE creator_suggestion ADD COLUMN evidence_refs JSON DEFAULT NULL COMMENT ''发布前优化可引用证据 JSON，记录任务材料、创作者偏好、类型语境和同类案例等依据'' AFTER partition_suggestion',
    'SELECT 1 AS ok'
) FROM INFORMATION_SCHEMA.COLUMNS
WHERE TABLE_SCHEMA = 'link_agent' AND TABLE_NAME = 'creator_suggestion' AND COLUMN_NAME = 'evidence_refs');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE creator_suggestion ADD COLUMN missing_info JSON DEFAULT NULL COMMENT ''缺失信息 JSON，记录会影响建议准确性但当前没有提供的信息'' AFTER evidence_refs',
    'SELECT 1 AS ok'
) FROM INFORMATION_SCHEMA.COLUMNS
WHERE TABLE_SCHEMA = 'link_agent' AND TABLE_NAME = 'creator_suggestion' AND COLUMN_NAME = 'missing_info');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE creator_suggestion ADD COLUMN generation_mode VARCHAR(64) DEFAULT NULL COMMENT ''生成模式：DIRECT_LLM_EVIDENCE=直连模型证据化，AGENT_RAG_EVIDENCE=Agent证据化'' AFTER missing_info',
    'SELECT 1 AS ok'
) FROM INFORMATION_SCHEMA.COLUMNS
WHERE TABLE_SCHEMA = 'link_agent' AND TABLE_NAME = 'creator_suggestion' AND COLUMN_NAME = 'generation_mode');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE creator_suggestion ADD COLUMN quality_status VARCHAR(64) DEFAULT NULL COMMENT ''质量状态：AUDIT_PASSED=审查通过，AUDIT_WARNED=存在警告，AUDIT_FAILED=存在错误，AUDIT_SKIPPED=未审查'' AFTER generation_mode',
    'SELECT 1 AS ok'
) FROM INFORMATION_SCHEMA.COLUMNS
WHERE TABLE_SCHEMA = 'link_agent' AND TABLE_NAME = 'creator_suggestion' AND COLUMN_NAME = 'quality_status');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE creator_suggestion ADD COLUMN audit_report JSON DEFAULT NULL COMMENT ''发布前优化建议审查报告 JSON，记录证据引用、夸大承诺和结构完整性检查结果'' AFTER quality_status',
    'SELECT 1 AS ok'
) FROM INFORMATION_SCHEMA.COLUMNS
WHERE TABLE_SCHEMA = 'link_agent' AND TABLE_NAME = 'creator_suggestion' AND COLUMN_NAME = 'audit_report');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
