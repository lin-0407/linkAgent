package com.link.linkagent.creator.creation.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.link.linkagent.creator.creation.asset.mapper.CreationAssetMapper;
import com.link.linkagent.creator.creation.asset.service.CreationAssetPersistence;
import com.link.linkagent.creator.creation.mapper.*;
import com.link.linkagent.creator.creation.service.*;
import com.link.linkagent.creator.creation.storyboard.service.StoryboardCodec;
import com.link.linkagent.creator.creation.voice.service.CreationVoiceProperties;
import com.link.linkagent.creator.creation.aivideo.service.CreationVideoProperties;
import com.link.linkagent.creator.creation.render.FixedProjectRendererProperties;
import com.link.linkagent.creator.creation.billing.BillingExchangeRate;
import com.link.linkagent.creator.media.config.CreatorMediaProperties;
import com.link.linkagent.creator.media.storage.ObjectStorageService;
import com.link.linkagent.creator.media.preflight.mapper.PreflightReviewMapper;
import com.link.linkagent.creator.media.preflight.model.PreflightReviewRecord;
import com.link.linkagent.creator.media.preflight.model.PreflightStepRecord;
import com.link.linkagent.creator.media.preflight.service.AudienceScreeningService;
import com.link.linkagent.creator.creation.storyboard.service.StoryboardTextGenerator;
import com.link.linkagent.llm.LLMService;
import com.link.linkagent.creator.media.upload.mapper.MediaUploadMapper;
import com.link.linkagent.settings.mapper.RuntimeSettingMapper;
import jakarta.validation.Validation;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mybatis.spring.*;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.MountableFile;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 只使用隔离 MySQL 与存储替身，验证真实事务和清理范围，不代表外部供应商或浏览器验收。 */
@Testcontainers
class CreationFinishIntegrationTest {
    @Container static final GenericContainer<?> MYSQL=new GenericContainer<>("mysql:8.4")
        .withEnv("MYSQL_ROOT_PASSWORD","finish-isolated-test").withEnv("MYSQL_ROOT_HOST","%").withEnv("MYSQL_DATABASE","link_agent")
        .withLabels(Map.of("linkagent.scope","creation-finish-test"))
        .withCopyFileToContainer(MountableFile.forClasspathResource("sql/init.sql"),"/docker-entrypoint-initdb.d/01-init.sql")
        .withExposedPorts(3306).waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(2)));
    static final ObjectMapper JSON=new ObjectMapper();
    static JdbcTemplate jdbc;static DataSourceTransactionManager transactions;static SqlSessionTemplate session;
    static CreationRuntimeService runtime;static MediaUploadMapper media;static StoryboardCodec codec;
    static jakarta.validation.ValidatorFactory validators;
    @TempDir Path directory;
    @BeforeAll static void setup()throws Exception {
        var source=new DriverManagerDataSource("jdbc:mysql://"+MYSQL.getHost()+":"+MYSQL.getMappedPort(3306)+"/link_agent?useSSL=false&allowPublicKeyRetrieval=true","root","finish-isolated-test");
        jdbc=new JdbcTemplate(source);transactions=new DataSourceTransactionManager(source);
        var configuration=new Configuration();configuration.setMapUnderscoreToCamelCase(true);
        for(var mapper:List.of(MediaUploadMapper.class,CreationRuntimeMapper.class,CreationWorkspaceMapper.class,CreationMediaMapper.class,
                CreationPackageMapper.class,ManualReviewMapper.class,CreationCleanupMapper.class,CreationAssetMapper.class,RuntimeSettingMapper.class,ProviderConfigurationMapper.class))configuration.addMapper(mapper);
        var factory=new SqlSessionFactoryBean();factory.setDataSource(source);factory.setConfiguration(configuration);factory.afterPropertiesSet();session=new SqlSessionTemplate(factory.getObject());
        media=session.getMapper(MediaUploadMapper.class);runtime=tx(new CreationRuntimeService(media,session.getMapper(CreationRuntimeMapper.class),session.getMapper(CreationWorkspaceMapper.class),session.getMapper(CreationMediaMapper.class),session.getMapper(CreationPackageMapper.class),JSON));
        validators=Validation.buildDefaultValidatorFactory();codec=new StoryboardCodec(JSON,validators.getValidator());
    }
    @AfterAll static void close(){if(validators!=null)validators.close();}

    @Test void manualReviewSettlesIntoTaskBudgetAndRetainsTheOriginalRunAcrossRetries(){
        String task=task(true),review=id(),version=id();var ledger=ledger();var mapping=session.getMapper(ManualReviewMapper.class);var billing=billing();
        ledger.accept(task,"default",review,version,5000);ledger.accept(task,"default",review,version,5000);
        assertThat(mapping.byReview(review)).hasSize(1);var first=mapping.byReview(review).getFirst();
        insertReview(task,review,version,"RUNNING");
        var sent=billing.reserve(review,"MEDIA_VIDEO","manual-video-"+review,new BigDecimal("0.1"),"隔离验证");assertThat(sent.markSent()).isTrue();assertThat(sent.markSent()).isFalse();
        billing.settle(review,"manual-video-"+review,new BigDecimal("0.04"),"manual-usage-"+review);
        assertThat(runtime.snapshot(task,"default").budget().knownCny()).isEqualByComparingTo("0.288");
        jdbc.update("UPDATE creator_preflight_review SET status='FAILED' WHERE review_id=?",review);ledger.beforeRetry(task,"default",review);
        assertThat(runtime.snapshot(task,"default").activeRun()).isNull();
        ledger.accept(task,"default",review,version,5000);jdbc.update("UPDATE creator_preflight_review SET status='RUNNING' WHERE review_id=?",review);
        assertThat(mapping.byReview(review)).hasSize(2);
        billing.settle(review,"manual-video-"+review,new BigDecimal("0.04"),"manual-usage-"+review);
        assertThat(runtime.snapshot(task,"default").budget().knownCny()).isEqualByComparingTo("0.288");
        assertThat(session.getMapper(CreationRuntimeMapper.class).charge(task,"default","manual-video-"+review).orElseThrow().runId()).isEqualTo(first.runId());
        jdbc.update("UPDATE creator_preflight_review SET status='COMPLETED' WHERE review_id=?",review);ledger.beforeRetry(task,"default",review);
        assertThat(runtime.snapshot(task,"default").activeRun()).isNull();assertThat(mapping.byReview(review)).extracting(ManualReviewMapper.Entry::status).containsExactlyInAnyOrder("FAILED","COMPLETED");
    }
    @Test void cancellationPreventsNewSendsAndPreservesUnknownSentCosts(){
        String task=task(true),review=id(),version=id();var ledger=ledger();var billing=billing();var mappings=session.getMapper(ManualReviewMapper.class);
        ledger.accept(task,"default",review,version,5000);insertReview(task,review,version,"RUNNING");
        var pending=billing.reserve(review,"MEDIA_VIDEO","pending-"+review,new BigDecimal("0.1"),"取消验证");
        var sent=billing.reserve(review,"MEDIA_SEGMENT","sent-"+review,new BigDecimal("0.05"),"取消验证");assertThat(sent.markSent()).isTrue();
        ledger.cancel(task,"default",review);assertThat(pending.markSent()).isFalse();
        assertThatThrownBy(()->billing.reserve(review,"MEDIA_SEGMENT","new-"+review,new BigDecimal("0.05"),"取消验证")).isInstanceOf(ResponseStatusException.class);
        jdbc.update("UPDATE creator_preflight_review SET status='CANCELLED' WHERE review_id=?",review);ledger.advance(mappings.byReview(review).getFirst());
        var budget=runtime.snapshot(task,"default");assertThat(budget.activeRun()).isNull();assertThat(budget.budget().reservedCny()).isZero();assertThat(budget.budget().unknownCny()).isEqualByComparingTo("0.36");assertThat(budget.budget().paidCallsBlocked()).isTrue();
        billing.settle(review,"sent-"+review,new BigDecimal("0.02"),"late-usage-"+review);
        assertThat(runtime.snapshot(task,"default").budget().unknownCny()).isZero();assertThat(runtime.snapshot(task,"default").budget().knownCny()).isEqualByComparingTo("0.144");
    }
    @Test void manualReviewDoesNotCreateBudgetForLegacyUploadsAndRejectsInsufficientFunds(){
        String legacy=task(false);ledger().accept(legacy,"default",id(),id(),5000);assertThat(runtime.snapshot(legacy,"default").initialized()).isFalse();
        String task=task(true);runtime.updateBudget(task,"default",runtime.snapshot(task,"default").workspaceRevision(),new BigDecimal("0.01"));
        assertThatThrownBy(()->ledger().accept(task,"default",id(),id(),5000)).isInstanceOf(ResponseStatusException.class);assertThat(runtime.snapshot(task,"default").activeRun()).isNull();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource(value={
            "NULL,100,true", "100,NULL,true", "NULL,NULL,true",
            "NULL,100,false", "100,NULL,false", "120,40,true", "0,0,true", "0,100,true"
    },nullValues="NULL")
    void audienceUsageMustRemainUnknownUntilBothTokenCountsAreKnown(Integer input,Integer output,boolean valid) {
        String task=task(true),reviewId=id(),version=id();var ledger=ledger();var billing=billing();
        ledger.accept(task,"default",reviewId,version,5000);insertReview(task,reviewId,version,"RUNNING");
        var mapper=mock(PreflightReviewMapper.class);
        when(mapper.listIssues(reviewId)).thenReturn(List.of());when(mapper.listEvidence(reviewId)).thenReturn(List.of());
        when(mapper.insertTextScreeningCall(anyString(),anyString(),anyString(),anyString(),anyString(),anyString())).thenReturn(1);
        when(mapper.insertAudienceScreening(any())).thenReturn(1);
        when(mapper.completeTextScreeningCall(anyString(),any(),any(),anyInt())).thenReturn(1);
        var personas=List.of("CASUAL","TARGET","CORE_FAN").stream().map(type->new AudienceScreeningService.PersonaOutput(
                type,"可能继续观看",List.of(),List.of(),List.of(),List.of(),List.of(),new BigDecimal("0.5"))).toList();
        String content=valid?JSON.valueToTree(new AudienceScreeningService.AudienceOutput(personas)).toString():"invalid-json";
        var generator=mock(StoryboardTextGenerator.class);var llm=mock(LLMService.class);
        when(generator.generate(anyString(),anyString(),eq(4096))).thenReturn(new StoryboardTextGenerator.Raw(content,input,output,"isolated-model"));
        var service=new AudienceScreeningService(mapper,llm,JSON,billing,generator);
        var review=new PreflightReviewRecord(null,reviewId,task,version,"default","processing","usage-key",null,"RUNNING","SCREEN_AUDIENCE",80,0L,false,1,3,null,null,null,"a".repeat(64),"{}","[]",null,BigDecimal.ONE,null,null,"USD",null,null,null,null,null,null);
        var step=new PreflightStepRecord(null,"usage-step",reviewId,"SCREEN_AUDIENCE",5,"RUNNING",1,"a".repeat(64),null,null,null,null,null,null,null,null);
        try {
            if(valid)assertThat(service.screen(review,step).personaCount()).isEqualTo(3);
            else assertThatThrownBy(()->service.screen(review,step)).isInstanceOf(AudienceScreeningService.AudienceScreeningException.class);
            var charge=jdbc.queryForMap("SELECT status,known_cost_cny FROM creator_creation_cost_entry WHERE task_id=?",task);
            var budget=runtime.snapshot(task,"default").budget();
            boolean unknown=input==null || output==null;
            assertThat(charge.get("status")).isEqualTo(unknown?"UNKNOWN":"SETTLED");
            assertThat(budget.unknownCny()).isEqualByComparingTo(unknown?"0.36":"0");
            assertThat(budget.reservedCny()).isZero();
            assertThat(budget.paidCallsBlocked()).isEqualTo(unknown);
            if(unknown) {
                assertThat(charge.get("known_cost_cny")).isNull();
                assertThatThrownBy(()->billing.reserve(reviewId,"MEDIA_TEXT_SCREENING","next-"+reviewId,new BigDecimal("0.05"),"缺失用量后的调用"))
                        .isInstanceOf(ResponseStatusException.class);
            } else {
                BigDecimal expected=new BigDecimal(input).multiply(new BigDecimal("0.2")).add(new BigDecimal(output).multiply(new BigDecimal("0.8")))
                        .divide(new BigDecimal("1000000")).multiply(new BigDecimal("7.2")).setScale(6,java.math.RoundingMode.HALF_UP);
                assertThat(budget.knownCny()).isEqualByComparingTo(expected);
                billing.reserve(reviewId,"MEDIA_TEXT_SCREENING","next-"+reviewId,new BigDecimal("0.05"),"完整用量后的调用");
            }
            verify(generator,times(1)).generate(anyString(),anyString(),eq(4096));verifyNoInteractions(llm);
        } finally {
            ledger.cancel(task,"default",reviewId);jdbc.update("UPDATE creator_preflight_review SET status='CANCELLED' WHERE review_id=?",reviewId);
            ledger.advance(session.getMapper(ManualReviewMapper.class).byReview(reviewId).getFirst());
        }
    }
    @Test void unreferencedReadyAssetCanBeDeletedWhileHistoricalBriefReferenceProtectsMedia(){
        String task=task(false),free=asset(task),used=asset(task);var mapper=session.getMapper(CreationAssetMapper.class);var service=tx(new CreationAssetPersistence(mapper));
        service.deleteReady(task,"default",free);service.deleteReady(task,"default",free);assertThat(mapper.find(task,"default",free).orElseThrow().isCleanupPending()).isTrue();
        jdbc.update("INSERT INTO creator_creation_brief(brief_id,task_id,owner_id,revision,idempotency_key,request_hash,document_json,source_json) VALUES (?,?,'default',1,'fixture',?,?,'{}')",id(),task,"a".repeat(64),"{\"assetIds\":[\""+used+"\"]}");
        assertThat(mapper.referenceCount(task,"default",used)).isEqualTo(1);assertThatThrownBy(()->service.deleteReady(task,"default",used)).isInstanceOf(ResponseStatusException.class);assertThat(mapper.find(task,"default",used).orElseThrow().getStatus()).isEqualTo("READY");
    }
    @Test void cleanupDeletesOnlyRecordedUnregisteredObjectsAndOnlyFinishedRunDirectory()throws Exception {
        String task=task(true);var run=runtime.startPreparation(task,"default","PREPARE",id(),runtime.snapshot(task,"default").workspaceRevision(),"{\"purpose\":\"SAMPLE\"}",BigDecimal.ZERO);runtime.cancel(task,"default",run.runId());
        String prefix="users/default/tasks/"+task+"/",orphan=prefix+"attempt/orphan.mp4",shared=prefix+"registered/video.mp4";
        insertAttempt(task,run.runId(),id(),"UPLOADING",objects(orphan));
        insertAttempt(task,run.runId(),id(),"UPLOADING",objects(shared));
        insertAttempt(task,run.runId(),id(),"REGISTERED",objects(shared));
        jdbc.update("UPDATE creator_creation_run SET completed_at=TIMESTAMPADD(SECOND,-2000,NOW(6)) WHERE run_id=?",run.runId());
        Files.createDirectories(directory.resolve(run.runId()));Files.writeString(directory.resolve(run.runId()).resolve("test.txt"),"临时文件");Files.createDirectories(directory.resolve("unrelated"));
        var props=new FixedProjectRendererProperties();props.setWorkRoot(directory.toString());var storage=mock(ObjectStorageService.class);var cleanup=session.getMapper(CreationCleanupMapper.class);
        assertThat(cleanup.references("test-bucket",shared)).isEqualTo(1);new CreationResourceCleanup(cleanup,props,storage,codec).clean();
        verify(storage).deleteObject("test-bucket",orphan);verify(storage,never()).deleteObject("test-bucket",shared);assertThat(Files.exists(directory.resolve(run.runId()))).isFalse();assertThat(Files.exists(directory.resolve("unrelated"))).isTrue();assertThat(cleanup.finishedRuns(900)).doesNotContain(run.runId());
    }
    @Test void supplierSettingsAreEncryptedRequireRestartAndCannotChangeDuringAnActiveRun()throws Exception {
        var video=new CreationVideoProperties();var voice=new CreationVoiceProperties();var mediaProps=new CreatorMediaProperties();var settings=session.getMapper(RuntimeSettingMapper.class);var mapper=session.getMapper(ProviderConfigurationMapper.class);
        String aes=Base64.getEncoder().encodeToString(new byte[32]);var service=new ProviderConfiguration(mapper,video,voice,mediaProps,JSON,aes,transactions);service.load();
        var saved=service.save(settingsInput(0,"video-test-key","speech-test-key"));assertThat(saved.restartRequired()).isTrue();assertThat(video.getModel()).isEmpty();
        String stored=mapper.saved().orElseThrow();assertThat(stored).doesNotContain("video-test-key","speech-test-key","videoApiKey","dashScopeApiKey");assertThat(JSON.writeValueAsString(saved)).doesNotContain("video-test-key","speech-test-key");
        String task=task(true);var guarded=tx(new CreationRuntimeService(media,session.getMapper(CreationRuntimeMapper.class),session.getMapper(CreationWorkspaceMapper.class),session.getMapper(CreationMediaMapper.class),session.getMapper(CreationPackageMapper.class),JSON,null,null,service));
        assertThatThrownBy(()->guarded.startPreparation(task,"default","PREPARE",id(),runtime.snapshot(task,"default").workspaceRevision(),"{\"purpose\":\"SAMPLE\"}",BigDecimal.ZERO)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("配置");
        var restarted=new ProviderConfiguration(mapper,video,voice,mediaProps,JSON,aes,transactions);restarted.load();assertThat(restarted.status().restartRequired()).isFalse();assertThat(video.getApiKey()).isEqualTo("video-test-key");assertThat(mediaProps.getPreflight().getDashScopeApiKey()).isEqualTo("speech-test-key");
        var run=runtime.startPreparation(task,"default","PREPARE",id(),runtime.snapshot(task,"default").workspaceRevision(),"{\"purpose\":\"SAMPLE\"}",BigDecimal.ZERO);
        assertThatThrownBy(()->restarted.save(settingsInput(1,"",""))).isInstanceOf(ResponseStatusException.class);runtime.cancel(task,"default",run.runId());
        restarted.save(settingsInput(1,"",""));assertThat(mapper.saved().orElseThrow()).contains(JSON.readTree(stored).path("videoKeyEnc").asText());
        assertThatThrownBy(()->restarted.save(settingsInput(1,"",""))).isInstanceOf(ResponseStatusException.class);
    }
    private static ProviderConfiguration.Save settingsInput(long revision,String videoKey,String speechKey){return new ProviderConfiguration.Save(revision,"compatible","https://video.example.invalid","test-model",videoKey,List.of(5,10),new BigDecimal("0.1"),"test-voice-model","test-voice",new BigDecimal("28"),speechKey);}
    private static ManualReviewLedger ledger(){return tx(new ManualReviewLedger(session.getMapper(ManualReviewMapper.class),runtime,codec,new CreationBillingProperties(),new CreatorMediaProperties(),BillingExchangeRate.fixed(new BigDecimal("7.2"))));}
    private static CreationMediaBilling billing(){return new CreationMediaBilling(session.getMapper(CreationMediaMapper.class),runtime,new CreationBillingProperties(),JSON,BillingExchangeRate.fixed(new BigDecimal("7.2")),session.getMapper(ManualReviewMapper.class));}
    private static String task(boolean budget){String id=id();jdbc.update("INSERT INTO creator_task(task_id,user_id,task_name,video_type,status) VALUES (?,'default','隔离收尾验证','知识分享','DRAFT')",id);media.adoptMediaWorkspace(id,"default");if(budget)runtime.initialize(id,"default",id(),runtime.snapshot(id,"default").workspaceRevision(),new BigDecimal("20"));return id;}
    private static String asset(String task){String asset=id();jdbc.update("INSERT INTO creator_creation_asset(asset_id,task_id,owner_id,idempotency_key,kind,file_name,content_type,expected_bytes,expected_sha256,bucket_name,source_key,status) VALUES (?,?,'default',?,'IMAGE','fixture.png','image/png',100,?,'test-bucket',?,'READY')",asset,task,id(),"a".repeat(64),"fixture/"+asset);return asset;}
    private static void insertReview(String task,String review,String version,String status){jdbc.update("INSERT INTO creator_preflight_review(review_id,task_id,version_id,owner_id,processing_job_id,idempotency_key,status,input_fingerprint,provider_snapshot) VALUES (?,?,?,'default',?,?,?,?,'{}')",review,task,version,id(),id(),status,"a".repeat(64));}
    private static void insertAttempt(String task,String run,String attempt,String status,com.fasterxml.jackson.databind.JsonNode objects){jdbc.update("INSERT INTO creator_creation_render_attempt(run_id,attempt_id,task_id,owner_id,lease_generation,version_id,production_plan_id,input_hash,bundle_hash,bucket_name,objects_json,status) VALUES (?,?,?,'default',1,?,?,?,?,'test-bucket',?,?)",run,attempt,task,id(),id(),"a".repeat(64),"b".repeat(64),objects.toString(),status);}
    private static String id(){return UUID.randomUUID().toString();}
    private static com.fasterxml.jackson.databind.JsonNode objects(String key){var result=JSON.createArrayNode();result.addObject().put("objectKey",key);return result;}
    @SuppressWarnings("unchecked")private static <T>T tx(T target){var factory=new ProxyFactory(target);factory.addAdvice(new TransactionInterceptor(transactions,new AnnotationTransactionAttributeSource()));return(T)factory.getProxy();}
}
