package com.link.linkagent.creator.profile;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.link.linkagent.creator.bilibili.mapper.CreatorBilibiliMapper;
import com.link.linkagent.creator.bilibili.model.BilibiliAccountRecord;
import com.link.linkagent.creator.bilibili.model.BilibiliVideoSyncPayload;
import com.link.linkagent.creator.bilibili.service.BilibiliVideoSyncPersistenceService;
import com.link.linkagent.creator.bilibili.service.BilibiliVideoSyncProvider;
import com.link.linkagent.creator.bilibili.service.CreatorBilibiliService;
import com.link.linkagent.creator.media.workflow.CreatorMediaWorkflowGateService;
import com.link.linkagent.creator.preference.mapper.CreatorPreferenceMapper;
import com.link.linkagent.creator.preference.service.CreatorPreferenceService;
import com.link.linkagent.creator.profile.mapper.CreatorEventMapper;
import com.link.linkagent.creator.profile.mapper.CreatorProfileMapper;
import com.link.linkagent.creator.profile.model.CreatorProfileRecord;
import com.link.linkagent.creator.profile.service.CreatorProfileService;
import com.link.linkagent.creator.report.mapper.CreatorReviewInvalidationMapper;
import com.link.linkagent.creator.report.model.CreatorReportRecord;
import com.link.linkagent.creator.task.mapper.CreatorTaskMapper;
import com.link.linkagent.creator.task.model.CreatorTaskRecord;
import com.link.linkagent.llm.LLMService;
import com.link.linkagent.prompt.service.PromptService;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.time.Duration;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 真实 MySQL/MyBatis 验证持久化竞态与初始化重放；模型为固定替身，不访问 B站或供应商。 */
class CreatorPersistenceRegressionIntegrationTest {
    private static final String PROFILE_JSON = "{\"styleTags\":[],\"toneGuide\":\"自然\",\"audienceView\":\"观众\"}";
    static GenericContainer<?> mysql;
    static DriverManagerDataSource source;
    static JdbcTemplate jdbc;
    static DataSourceTransactionManager transactions;
    static CreatorPreferenceMapper preferences;
    static CreatorProfileMapper profiles;
    static CreatorEventMapper events;
    static CreatorBilibiliMapper bilibili;
    static CreatorReviewInvalidationMapper invalidation;

    @BeforeAll
    static void setup() throws Exception {
        // 可显式复用本轮隔离库，默认由测试自己创建并关闭容器。
        String url = System.getProperty("linkagent.persistence.jdbc-url");
        if (url == null) {
            mysql = new GenericContainer<>("mysql:8.4")
                    .withEnv("MYSQL_ROOT_PASSWORD", "bugfix-isolated-only")
                    .withEnv("MYSQL_ROOT_HOST", "%")
                    .withEnv("MYSQL_DATABASE", "link_agent")
                    .withLabel("linkagent.scope", "persistence-regression-test")
                    .withExposedPorts(3306)
                    .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(2)));
            mysql.start();
            url = "jdbc:mysql://" + mysql.getHost() + ":" + mysql.getMappedPort(3306)
                    + "/link_agent?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";
        }
        source = new DriverManagerDataSource(url, "root", "bugfix-isolated-only");
        jdbc = new JdbcTemplate(source);
        transactions = new DataSourceTransactionManager(source);
        replayInit();
        var configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        for (Class<?> mapper : List.of(CreatorPreferenceMapper.class, CreatorProfileMapper.class,
                CreatorEventMapper.class, CreatorBilibiliMapper.class, CreatorReviewInvalidationMapper.class)) {
            configuration.addMapper(mapper);
        }
        var factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setConfiguration(configuration);
        factory.afterPropertiesSet();
        var session = new SqlSessionTemplate(factory.getObject());
        preferences = session.getMapper(CreatorPreferenceMapper.class);
        profiles = session.getMapper(CreatorProfileMapper.class);
        events = session.getMapper(CreatorEventMapper.class);
        bilibili = session.getMapper(CreatorBilibiliMapper.class);
        invalidation = session.getMapper(CreatorReviewInvalidationMapper.class);
    }

    @AfterAll
    static void cleanup() {
        if (mysql != null) mysql.stop();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void adoptionHistoryAndCurrentReportCoexistInEitherWriteOrder(boolean reportFirst) {
        var service = tx(new CreatorPreferenceService(preferences));
        String user = id();
        var task = task(user);
        var first = report("第一份复盘偏好");
        if (reportFirst) service.saveFromReport(task, first);
        service.recordAdoptionFeedback(user, task.getTaskId(), "ADOPTED", "采用标题一");
        if (!reportFirst) service.saveFromReport(task, first);
        service.recordAdoptionFeedback(user, task.getTaskId(), "ADOPTED", "采用标题二");
        service.recordAdoptionFeedback(user, task.getTaskId(), "REJECTED", "拒绝标题三");
        service.saveFromReport(task, first);
        assertThat(preferences.listByUserId(user, 20)).hasSize(4);
        assertThat(preferences.listAdoptionFeedbackByUserId(user, 20)).hasSize(3)
                .extracting(record -> record.getSourceReportId()).doesNotHaveDuplicates();
        assertThat(service.buildPromptContext(user)).contains("第一份复盘偏好", "采用标题一", "采用标题二", "拒绝标题三");

        var second = report("新的复盘偏好");
        service.saveFromReport(task, second);
        assertThat(preferences.listReportPreferencesByUserId(user, 20)).hasSize(1)
                .first().extracting(record -> record.getSourceReportId()).isEqualTo(second.getReportId());
        assertThat(service.buildPromptContext(user)).doesNotContain("第一份复盘偏好");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM creator_preference WHERE user_id=?", Integer.class, user))
                .isEqualTo(5);
        invalidation.invalidateGeneratedPreference(task.getTaskId());
        assertThat(preferences.listByUserId(user, 20)).hasSize(3);
        assertThat(service.buildPromptContext(user)).contains("采用标题一").doesNotContain("新的复盘偏好");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void staleSuccessfulOrFailedSyncCannotWriteBackAfterUidChanges(boolean providerFails) {
        String user = id();
        var account = new BilibiliAccountRecord(null, id(), user, "111111", "旧昵称", "旧头像",
                "ACTIVE", null, null, null, null);
        bilibili.insertAccount(account);
        var provider = mock(BilibiliVideoSyncProvider.class);
        when(provider.fetch(eq("111111"), anyList())).thenAnswer(call -> {
            bilibili.updateAccountUid(account.getAccountId(), "222222");
            if (providerFails) throw new IllegalStateException("受控外部请求失败");
            return new BilibiliVideoSyncPayload("111111", "迟到昵称", "迟到头像", false, false,
                    List.of(), List.of(), List.of());
        });
        var persistence = tx(new BilibiliVideoSyncPersistenceService(bilibili,
                mock(CreatorMediaWorkflowGateService.class)));
        var service = new CreatorBilibiliService(bilibili, mock(CreatorTaskMapper.class), provider,
                persistence, mock(CreatorMediaWorkflowGateService.class));
        assertThatThrownBy(() -> service.syncVideos(user)).isInstanceOf(ResponseStatusException.class);
        var after = bilibili.findAccountByUserId(user).orElseThrow();
        assertThat(after.getBilibiliUid()).isEqualTo("222222");
        assertThat(after.getNickname()).isNull();
        assertThat(after.getAvatarUrl()).isNull();
        assertThat(after.getLastSyncTime()).isNull();
        assertThat(after.getLastSyncError()).isNull();
        assertThat(after.getBindStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void unchangedProfileConsumesOnlyAnalyzedBatchAndLeavesEventsArrivingDuringCall() {
        String user = profile();
        var llm = model();
        var service = profileService(llm);
        addEvents(service, user, 10);
        when(llm.chat(anyString(), anyString())).thenAnswer(call -> {
            addEvents(service, user, 5);
            return PROFILE_JSON;
        });
        service.tryTriggerProfileUpdate(user);
        long firstCursor = profiles.findByCreatorId(user).getLastProcessedEventId();
        assertThat(firstCursor).isPositive();
        assertThat(events.countNewEvents(user, firstCursor)).isEqualTo(5);
        service.tryTriggerProfileUpdate(user);
        verify(llm).chat(anyString(), anyString());
        when(llm.chat(anyString(), anyString())).thenReturn(PROFILE_JSON);
        service.refreshProfile(user);
        assertThat(events.countNewEvents(user, profiles.findByCreatorId(user).getLastProcessedEventId())).isZero();
        service.tryTriggerProfileUpdate(user);
        verify(llm, times(2)).chat(anyString(), anyString());
    }

    @Test
    void backlogIsConsumedInOrderedBatchesWithoutSkippingOlderEvents() {
        String user = profile();
        var llm = model();
        var service = profileService(llm);
        addEvents(service, user, 61);
        service.tryTriggerProfileUpdate(user);
        assertThat(events.countNewEvents(user, profiles.findByCreatorId(user).getLastProcessedEventId())).isEqualTo(11);
        service.tryTriggerProfileUpdate(user);
        assertThat(events.countNewEvents(user, profiles.findByCreatorId(user).getLastProcessedEventId())).isZero();
        service.tryTriggerProfileUpdate(user);
        verify(llm, times(2)).chat(anyString(), anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{invalid}", "{}", "{\"styleTags\":[42],\"toneGuide\":\"新内容\",\"audienceView\":\"观众\"}"})
    void invalidModelOutputPreservesCursorAndReleasesClaimForRetry(String invalid) {
        String user = profile();
        var llm = model();
        when(llm.chat(anyString(), anyString())).thenReturn(invalid, PROFILE_JSON);
        var service = profileService(llm);
        addEvents(service, user, 10);
        service.tryTriggerProfileUpdate(user);
        assertThat(profiles.findByCreatorId(user).getLastProcessedEventId()).isZero();
        assertThat(profiles.findByCreatorId(user).getToneGuide()).isEqualTo("自然");
        service.tryTriggerProfileUpdate(user);
        assertThat(events.countNewEvents(user, profiles.findByCreatorId(user).getLastProcessedEventId())).isZero();
        verify(llm, times(2)).chat(anyString(), anyString());
    }

    @Test
    void failedModelCallReleasesClaimAndCanRetry() {
        String user = profile();
        var llm = model();
        when(llm.chat(anyString(), anyString())).thenThrow(new IllegalStateException("受控失败")).thenReturn(PROFILE_JSON);
        var service = profileService(llm);
        addEvents(service, user, 10);
        service.tryTriggerProfileUpdate(user);
        assertThat(profiles.findByCreatorId(user).getLastProcessedEventId()).isZero();
        service.tryTriggerProfileUpdate(user);
        assertThat(events.countNewEvents(user, profiles.findByCreatorId(user).getLastProcessedEventId())).isZero();
    }

    @Test
    void concurrentTriggersCallModelOnce() throws Exception {
        String user = profile();
        var llm = model();
        var service = profileService(llm);
        addEvents(service, user, 10);
        var started = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        when(llm.chat(anyString(), anyString())).thenAnswer(call -> {
            started.countDown();
            assertThat(finish.await(10, TimeUnit.SECONDS)).isTrue();
            return PROFILE_JSON;
        });
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> service.tryTriggerProfileUpdate(user));
            assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
            try {
                pool.submit(() -> service.tryTriggerProfileUpdate(user)).get(5, TimeUnit.SECONDS);
                verify(llm).chat(anyString(), anyString());
            } finally {
                finish.countDown();
            }
            first.get(10, TimeUnit.SECONDS);
        }
        assertThat(events.countNewEvents(user, profiles.findByCreatorId(user).getLastProcessedEventId())).isZero();
    }

    @Test
    void expiredClaimCanBeReplacedAndOldTokenCannotOverwriteOrReleaseNewOwner() {
        String user = profile();
        assertThat(profiles.tryClaimRefresh(user, 0, "old")).isEqualTo(1);
        jdbc.update("UPDATE creator_profile SET refresh_started_at=DATE_SUB(NOW(),INTERVAL 11 MINUTE) WHERE creator_id=?", user);
        assertThat(profiles.tryClaimRefresh(user, 0, "new")).isEqualTo(1);
        var stale = profiles.findByCreatorId(user);
        stale.setToneGuide("旧调用迟到内容");
        stale.setLastProcessedEventId(999);
        assertThat(profiles.update(stale, "old")).isZero();
        assertThat(profiles.releaseRefresh(user, "old")).isZero();
        assertThat(profiles.findByCreatorId(user).getToneGuide()).isEqualTo("自然");
        assertThat(profiles.releaseRefresh(user, "new")).isEqualTo(1);
    }

    @Test
    void initialProfileKeepsPreexistingEventsPending() {
        String user = id();
        var llm = model();
        var service = profileService(llm);
        addEvents(service, user, 10);
        assertThat(service.ensureProfile(user).getLastProcessedEventId()).isZero();
        service.tryTriggerProfileUpdate(user);
        assertThat(events.countNewEvents(user, profiles.findByCreatorId(user).getLastProcessedEventId())).isZero();
        verify(llm).chat(anyString(), anyString());
    }

    @Test
    void replayedInitializationPreservesLegalLatinChineseAndSymbolPrompts() throws Exception {
        Map<String, String> custom = Map.of(
                "field_autofill.system", "请保留 François、Mädchen 的原文拼写，并用中文解释。",
                "field_autofill.user", "用涓涓细流描述画面，保留这个人工提示词。",
                "creator_profile.init.system", "合法符号 ??? Ã Â ä å æ ç 鍙 涓 瀹 鐨 銆 和 emoji 🐟 都应原样保留");
        Map<String, String> original = new LinkedHashMap<>();
        custom.forEach((key, content) -> original.put(key, jdbc.queryForObject(
                "SELECT content FROM llm_prompt_template WHERE prompt_key=?", String.class, key)));
        try {
            custom.forEach((key, content) -> jdbc.update("UPDATE llm_prompt_template SET content=? WHERE prompt_key=?", content, key));
            replayInit();
            replayInit();
            custom.forEach((key, content) -> assertThat(jdbc.queryForObject(
                    "SELECT content FROM llm_prompt_template WHERE prompt_key=?", String.class, key)).isEqualTo(content));
        } finally {
            original.forEach((key, content) -> jdbc.update("UPDATE llm_prompt_template SET content=? WHERE prompt_key=?", content, key));
        }
    }

    private static void replayInit() throws Exception {
        try (var connection = source.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("sql/init.sql"));
        }
    }

    private CreatorProfileService profileService(LLMService llm) {
        var prompt = mock(PromptService.class);
        when(prompt.get(anyString())).thenReturn("受控画像提示词");
        when(prompt.render(anyString(), anyMap())).thenReturn("受控事件输入");
        return new CreatorProfileService(events, profiles, preferences, llm, prompt, new ObjectMapper());
    }

    private LLMService model() {
        var llm = mock(LLMService.class);
        when(llm.chat(anyString(), anyString())).thenReturn(PROFILE_JSON);
        return llm;
    }

    private String profile() {
        var profile = new CreatorProfileRecord();
        profile.setCreatorId(id());
        profile.setStyleTags("[]");
        profile.setToneGuide("自然");
        profile.setAudienceView("观众");
        profiles.insert(profile);
        return profile.getCreatorId();
    }

    private void addEvents(CreatorProfileService service, String user, int count) {
        for (int index = 0; index < count; index++) service.recordEvent(user, "ADOPTED", "test-task", Map.of("index", index));
    }

    private CreatorTaskRecord task(String user) {
        var task = new CreatorTaskRecord();
        task.setTaskId(id());
        task.setUserId(user);
        return task;
    }

    private CreatorReportRecord report(String content) {
        var report = new CreatorReportRecord();
        report.setReportId(id());
        report.setParseStatus("PARSED");
        report.setCreatorPreferenceInsight(content);
        return report;
    }

    private static String id() {
        return UUID.randomUUID().toString();
    }

    @SuppressWarnings("unchecked")
    private static <T> T tx(T service) {
        var proxy = new ProxyFactory(service);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        return (T) proxy.getProxy();
    }
}
