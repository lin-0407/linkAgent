-- 质量分少样本展示修正升级脚本。
-- 用于已经存在 creator_reference_video 表的开发库；全新建库直接使用 init.sql 即可。
-- 执行后请调用 POST /api/knowledge/reference-videos/quality/recompute，
-- 用当前 Java 配置重算 raw_quality_score / quality_score，避免 SQL 脚本里硬编码公式参数。

ALTER TABLE creator_reference_video
    ADD COLUMN raw_quality_score DECIMAL(12, 6) DEFAULT NULL COMMENT '单视频独立原始质量分，由互动率和情绪因子直接计算；不依赖同分区其它视频，用于小样本兜底排序' AFTER highlight_summary,
    MODIFY COLUMN quality_score DECIMAL(6, 2) DEFAULT NULL COMMENT '分区归一化相对质量分（0–100）；同分区有效样本不足或原始分无差异时为空，避免少样本展示 60/0/100 误导用户',
    ADD COLUMN quality_sample_count INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '本次分区归一化可参与打分的有效视频数；用于判断 quality_score 是否具备展示可信度' AFTER quality_score,
    ADD COLUMN quality_score_reliable TINYINT NOT NULL DEFAULT 0 COMMENT '质量分是否达到展示可信度：1=可展示相对质量分，0=仅保留原始分作内部排序或样本不足' AFTER quality_sample_count;

-- 旧 quality_score 可能是孤例 60 或双样本 0/100；加列后先清空，避免重算前继续影响检索排序。
UPDATE creator_reference_video
SET raw_quality_score = NULL,
    quality_score = NULL,
    quality_sample_count = 0,
    quality_score_reliable = 0
WHERE is_deleted = 0;
