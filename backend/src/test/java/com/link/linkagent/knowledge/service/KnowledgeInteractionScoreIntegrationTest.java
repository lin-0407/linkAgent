package com.link.linkagent.knowledge.service;

import com.link.linkagent.knowledge.config.KnowledgeQualityProperties;
import com.link.linkagent.knowledge.mapper.KnowledgeReferenceVideoMapper;
import com.link.linkagent.knowledge.model.ReferenceVideoRecord;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 独立 MySQL 核对整区排名、分页和索引失效，不连接作者已有的数据或服务。 */
@Testcontainers
class KnowledgeInteractionScoreIntegrationTest {
    @Container
    static final GenericContainer<?> MYSQL = new GenericContainer<>("mysql:8.4")
            .withEnv("MYSQL_ROOT_PASSWORD", "interaction-score-isolated-test")
            .withEnv("MYSQL_DATABASE", "link_agent")
            .withCopyFileToContainer(MountableFile.forClasspathResource("sql/init.sql"), "/docker-entrypoint-initdb.d/01-init.sql")
            .withExposedPorts(3306)
            .waitingFor(Wait.forLogMessage(".*ready for connections.*port: 3306.*\\n", 1)
                    .withStartupTimeout(Duration.ofMinutes(2)));
    private static KnowledgeReferenceVideoMapper mapper;
    private static KnowledgeQualityScoringService scoring;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void setup() throws Exception {
        var dataSource = new DriverManagerDataSource("jdbc:mysql://" + MYSQL.getHost() + ":" + MYSQL.getMappedPort(3306)
                + "/link_agent?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai",
                "root", "interaction-score-isolated-test");
        var configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(KnowledgeReferenceVideoMapper.class);
        var factory = new SqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setConfiguration(configuration);
        factory.afterPropertiesSet();
        mapper = new SqlSessionTemplate(factory.getObject()).getMapper(KnowledgeReferenceVideoMapper.class);
        scoring = new KnowledgeQualityScoringService(mapper, new KnowledgeQualityProperties());
        jdbc = new JdbcTemplate(dataSource);
    }

    @BeforeEach
    void clearOwnDatabase() {
        jdbc.update("DELETE FROM creator_reference_video");
    }

    @Test
    void paginationTierKeywordAndCandidateFiltersMustKeepWholeCategoryRanks() {
        insert("first", "知识", "BENCHMARK", 200_000, 2);
        insert("second", "知识", "COMPETITOR", 100_000, 1);
        insert("third", "知识", "BENCHMARK", 50_000, 0.5);
        scoring.recomputeCategories(Set.of("知识"));

        var page = mapper.listReferenceVideos("知识", "COMPETITOR", 1, 0);
        assertThat(page).hasSize(1);
        assertThat(page.getFirst().getVideoId()).isEqualTo("second");
        assertThat(page.getFirst().getCategoryRank()).isEqualTo(2);
        assertThat(page.getFirst().getQualitySampleCount()).isEqualTo(3);
        assertThat(mapper.listReferenceVideos("知识", null, 1, 1).getFirst().getCategoryRank()).isEqualTo(2);
        assertThat(mapper.findByVideoId("third").orElseThrow().getCategoryRank()).isEqualTo(3);
        assertThat(mapper.listByVideoIds(List.of("second")).getFirst().getCategoryRank()).isEqualTo(2);
        assertThat(mapper.searchByKeyword("知识", null, "second", List.of("second"), 10).getFirst().getCategoryRank()).isEqualTo(2);
        assertThat(mapper.listByVideoIdsOrderByQuality(List.of("third", "first"), "知识", null, 0, 10))
                .extracting(ReferenceVideoRecord::getVideoId).containsExactly("first", "third");
    }

    @Test
    void anotherCategoryAndKeywordSentimentMustNotChangeExistingScores() {
        insert("one", "知识", "BENCHMARK", 100_000, 1);
        scoring.recomputeOnStartup();
        var before = mapper.findByVideoId("one").orElseThrow();
        assertThat(before.getQualityScore()).isEqualByComparingTo("47.62");
        assertThat(before.getCategoryRank()).isEqualTo(1);
        insert("other", "游戏", "BENCHMARK", 9_000_000, 1000);
        jdbc.update("INSERT INTO creator_reference_video_item(item_id,video_id,content,sentiment,source_type) VALUES (?,?,?,?,?)",
                "negative-demand", "one", "催更，不是一般的好", "NEGATIVE", "COMMENT");
        scoring.recomputeAllCategories();
        var after = mapper.findByVideoId("one").orElseThrow();
        assertThat(after.getQualityScore()).isEqualByComparingTo(before.getQualityScore());
        assertThat(after.getQualitySampleCount()).isEqualTo(1);
        assertThat(after.getCategoryRank()).isEqualTo(1);
    }

    @Test
    void tiesMissingStatisticsAndExpiredCasesMustHaveCorrectRanksAndCounts() {
        insert("tie-a", "日常", "BENCHMARK", 100_000, 1);
        insert("tie-b", "日常", "BENCHMARK", 100_000, 1);
        insert("lower", "日常", "BENCHMARK", 50_000, 0.5);
        insert("missing", "日常", "BENCHMARK", 100_000, 1);
        insert("expired", "日常", "BENCHMARK", 900_000, 100);
        jdbc.update("UPDATE creator_reference_video SET favorite_count=NULL WHERE video_id='missing'");
        jdbc.update("UPDATE creator_reference_video SET published_at=DATE_SUB(DATE_ADD(UTC_TIMESTAMP(),INTERVAL 8 HOUR),INTERVAL 2 YEAR) WHERE video_id='expired'");
        scoring.recomputeCategories(Set.of("日常"));
        assertThat(mapper.findByVideoId("tie-a").orElseThrow().getCategoryRank()).isEqualTo(1);
        assertThat(mapper.findByVideoId("tie-b").orElseThrow().getCategoryRank()).isEqualTo(1);
        assertThat(mapper.findByVideoId("lower").orElseThrow().getCategoryRank()).isEqualTo(3);
        assertThat(mapper.findByVideoId("lower").orElseThrow().getQualitySampleCount()).isEqualTo(3);
        var missing = mapper.findByVideoId("missing").orElseThrow();
        assertThat(missing.getQualityScore()).isNull();
        assertThat(missing.getCategoryRank()).isNull();
        assertThat(mapper.findByVideoId("expired")).isEmpty();
    }

    @Test
    void changedScoresMustInvalidateOldParentIndexButRepeatedRecomputeMustKeepFreshIndex() {
        insert("indexed", "日常", "BENCHMARK", 100_000, 1);
        jdbc.update("UPDATE creator_reference_video SET quality_score=100,raw_quality_score=2,embedding_status='INDEXED'");
        scoring.recomputeCategories(Set.of("日常"));
        assertThat(mapper.findByVideoId("indexed").orElseThrow().getEmbeddingStatus()).isEqualTo("PENDING");
        jdbc.update("UPDATE creator_reference_video SET embedding_status='INDEXED'");
        scoring.recomputeCategories(Set.of("日常"));
        assertThat(mapper.findByVideoId("indexed").orElseThrow().getEmbeddingStatus()).isEqualTo("INDEXED");
        assertThat(mapper.findByVideoId("indexed").orElseThrow().getVideoIndexScore()).isEqualByComparingTo("47.619048");
    }

    private void insert(String id, String category, String tier, long views, double factor) {
        var row = new ReferenceVideoRecord();
        row.setVideoId(id);
        row.setTitle(id);
        row.setCategory(category);
        row.setTier(tier);
        row.setSource("seed");
        row.setViewCount(views);
        row.setLikeCount((long) (1000 * factor));
        row.setCoinCount((long) (2000 * factor));
        row.setFavoriteCount((long) (1000 * factor));
        row.setReplyCount((long) (1000 * factor));
        row.setDanmakuCount((long) (1000 * factor));
        mapper.insertReferenceVideo(row);
    }
}
