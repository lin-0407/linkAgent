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
