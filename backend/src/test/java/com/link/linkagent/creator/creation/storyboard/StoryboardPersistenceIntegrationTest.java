package com.link.linkagent.creator.creation.storyboard;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.link.linkagent.creator.creation.asset.mapper.CreationAssetMapper;
import com.link.linkagent.creator.creation.asset.model.AssetRequests.StoredFile;
import com.link.linkagent.creator.creation.asset.service.CreationAssetPersistence;
import com.link.linkagent.creator.creation.asset.service.CreationAssetService;
import com.link.linkagent.creator.creation.mapper.*;
import com.link.linkagent.creator.creation.model.CreationRuntimeModels.Run;
import com.link.linkagent.creator.creation.service.CreationBillingProperties;
import com.link.linkagent.creator.creation.service.CreationRuntimeService;
import com.link.linkagent.creator.creation.storyboard.mapper.StoryboardMapper;
import com.link.linkagent.creator.creation.storyboard.model.StoryboardModels.*;
import com.link.linkagent.creator.creation.storyboard.service.StoryboardCodec;
import com.link.linkagent.creator.creation.storyboard.service.StoryboardPersistence;
import com.link.linkagent.creator.creation.storyboard.service.StoryboardTextGenerator;
import com.link.linkagent.creator.creation.storyboard.service.StoryboardWorker;
import com.link.linkagent.creator.creation.storyboard.service.StoryboardAudioService;
import com.link.linkagent.creator.creation.storyboard.service.StoryboardCompiler;
import com.link.linkagent.creator.creation.storyboard.model.StoryboardAudioModels.*;
import com.link.linkagent.creator.creation.execution.*;
import com.link.linkagent.creator.creation.revision.*;
import com.link.linkagent.creator.creation.service.CreationWorkerProperties;
import com.link.linkagent.creator.creation.service.CreationCapabilityGate;
import com.link.linkagent.creator.creation.service.AutomaticProductionPlanService;
import com.link.linkagent.creator.creation.billing.BillingExchangeRate;
import com.link.linkagent.creator.creation.render.FixedProjectRendererProperties;
import com.link.linkagent.creator.creation.voice.service.CreationVoiceService;
import com.link.linkagent.creator.creation.aivideo.service.CreationVideoService;
import com.link.linkagent.creator.media.config.CreatorMediaProperties;
import com.link.linkagent.creator.media.config.ObjectStorageProperties;
import com.link.linkagent.creator.media.storage.ObjectStorageService;
import com.link.linkagent.creator.media.upload.mapper.MediaUploadMapper;
import com.link.linkagent.creator.task.mapper.CreatorTaskMapper;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.mockito.ArgumentCaptor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.*;

/** 真实 MySQL 验证简报、素材引用和运行事务；素材字节及模型候选均为受控夹具，不代表实际上传或供应商生成。 */
@Testcontainers
class StoryboardPersistenceIntegrationTest {
    @Container
    static final GenericContainer<?> MYSQL = new GenericContainer<>("mysql:8.4")
            .withEnv("MYSQL_ROOT_PASSWORD", "storyboard-isolated-test")
            .withEnv("MYSQL_ROOT_HOST", "%")
            .withEnv("MYSQL_DATABASE", "link_agent")
            .withLabels(Map.of("linkagent.scope", "creation-s4-storyboard-test"))
            .withCopyFileToContainer(MountableFile.forClasspathResource("sql/init.sql"),
                    "/docker-entrypoint-initdb.d/01-init.sql")
            .withExposedPorts(3306)
            .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(2)));

    static final ObjectMapper JSON = new ObjectMapper();
    static JdbcTemplate jdbc;
    static DataSourceTransactionManager transactions;
    static SqlSessionTemplate session;
    static MediaUploadMapper media;
    static CreationRuntimeMapper runs;
    static CreationWorkspaceMapper workspace;
    static StoryboardMapper mapper;
    static CreatorTaskMapper tasks;
    static CreationRuntimeService runtime;
    static CreationAssetService assets;
    static StoryboardCodec codec;
    static ValidatorFactory validators;
    StoryboardTextGenerator generator;
    StoryboardPersistence persistence;
    CreationBillingProperties prices;

    @BeforeAll
    static void setup() throws Exception {
        var source = new DriverManagerDataSource("jdbc:mysql://" + MYSQL.getHost() + ":"
                + MYSQL.getMappedPort(3306) + "/link_agent?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai",
                "root", "storyboard-isolated-test");
        jdbc = new JdbcTemplate(source);
        transactions = new DataSourceTransactionManager(source);
        var configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        for (Class<?> type : List.of(MediaUploadMapper.class, CreationRuntimeMapper.class,
                CreationWorkspaceMapper.class, CreationMediaMapper.class, CreationPackageMapper.class,
                CreatorTaskMapper.class, CreationAssetMapper.class, StoryboardMapper.class, ExecutionMapper.class, RevisionMapper.class)) {
            configuration.addMapper(type);
        }
        var factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setConfiguration(configuration);
        factory.afterPropertiesSet();
        session = new SqlSessionTemplate(factory.getObject());
        media = session.getMapper(MediaUploadMapper.class);
        runs = session.getMapper(CreationRuntimeMapper.class);
        workspace = session.getMapper(CreationWorkspaceMapper.class);
        mapper = session.getMapper(StoryboardMapper.class);
        tasks = session.getMapper(CreatorTaskMapper.class);
        runtime = tx(new CreationRuntimeService(media, runs, workspace,
                session.getMapper(CreationMediaMapper.class), session.getMapper(CreationPackageMapper.class), JSON));
        var assetMapper = session.getMapper(CreationAssetMapper.class);
        assets = new CreationAssetService(assetMapper, tx(new CreationAssetPersistence(assetMapper)),
                new ObjectStorageProperties(), mock(ObjectStorageService.class), JSON);
        validators = Validation.buildDefaultValidatorFactory();
        codec = new StoryboardCodec(JSON, validators.getValidator());
    }

    @AfterAll
    static void closeValidationFactory() {
        if (validators != null) validators.close();
    }

    @BeforeEach
    void boundaries() {
        generator = mock(StoryboardTextGenerator.class);
        when(generator.modelName()).thenReturn("configured-test-text-model");
        prices = new CreationBillingProperties();
        persistence = service(mapper);
    }

    @Test
    void briefVersionsFreezeRealMaterialsAndSameKeyReplaysOriginalWithoutBumping() {
        String task = task("default");
        String sourceId = material(task, "最初由用户输入的事实");
        var input = brief(revision(task), List.of());
        var first = persistence.saveBrief(task, "default", "brief-first", input);
        assertThat(first.revision()).isEqualTo(1);
        assertThat(codec.read(first.sourceJson()).path("materials").get(0).path("sourceId").asText()).isEqualTo(sourceId);
        assertThat(codec.read(first.sourceJson()).path("materials").get(0).path("sha256").asText())
                .isEqualTo(StoryboardCodec.digest("最初由用户输入的事实"));
        assertThat(codec.read(first.documentJson()).has("expectedWorkspaceRevision")).isFalse();
        long afterFirst = revision(task);
        jdbc.update("UPDATE creator_material SET content='后来修改的材料' WHERE task_id=?", task);
        var replay = persistence.saveBrief(task, "default", "brief-first", input);
        assertThat(replay.briefId()).isEqualTo(first.briefId());
        assertThat(replay.sourceJson()).isEqualTo(first.sourceJson());
        assertThat(revision(task)).isEqualTo(afterFirst);
        var second = persistence.saveBrief(task, "default", "brief-second", brief(afterFirst, List.of()));
        assertThat(second.revision()).isEqualTo(2);
        assertThat(codec.read(second.sourceJson()).path("materials").get(0).path("content").asText()).isEqualTo("后来修改的材料");
        assertThat(mapper.brief(task, "default", first.briefId()).orElseThrow().sourceJson()).isEqualTo(first.sourceJson());
        assertThat(count("creator_creation_brief", task)).isEqualTo(2);
        verify(generator, never()).generate(anyString(), anyString(), anyInt());
    }

    @Test
    void sameBriefKeyWithDifferentContentIsConflictAndOriginalSurvives() {
        String task = task("default");
        var input = brief(revision(task), List.of());
        var first = persistence.saveBrief(task, "default", "same-key", input);
        var changed = new BriefInput(input.expectedWorkspaceRevision(), "另一个主题", input.coreMessage(),
                input.audience(), input.targetDurationMs(), input.dialogueMode(), input.contentKind(), input.assetIds(), input.constraints());
        conflict(() -> persistence.saveBrief(task, "default", "same-key", changed));
        assertThat(count("creator_creation_brief", task)).isEqualTo(1);
        assertThat(mapper.brief(task, "default", first.briefId()).orElseThrow().documentJson()).isEqualTo(first.documentJson());
        assertThat(revision(task)).isEqualTo(1);
    }

    @Test
    void concurrentBriefReplayCreatesOnlyOneImmutableVersion() throws Exception {
        String task = task("default");
        var input = brief(revision(task), List.of());
        try (var executor = Executors.newFixedThreadPool(3)) {
            var futures = executor.<Brief>invokeAll(List.of(
                    () -> persistence.saveBrief(task, "default", "concurrent", input),
                    () -> persistence.saveBrief(task, "default", "concurrent", input),
                    () -> persistence.saveBrief(task, "default", "concurrent", input)));
            String first = futures.getFirst().get(10, TimeUnit.SECONDS).briefId();
            for (var future : futures) assertThat(future.get(10, TimeUnit.SECONDS).briefId()).isEqualTo(first);
        }
        assertThat(count("creator_creation_brief", task)).isEqualTo(1);
        assertThat(revision(task)).isEqualTo(1);
    }

    @Test
    void absentMusicKeepsLegacyBriefRequestHashAndReplayCompatible() throws Exception {
        String task=task("default");var input=brief(0,List.of());
        var legacy=JSON.valueToTree(input).deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)legacy).remove("backgroundMusic");
        String oldHash=codec.hash(legacy);
        var saved=persistence.saveBrief(task,"default","legacy-no-music",input);
        assertThat(saved.requestHash()).isEqualTo(oldHash);
        assertThat(codec.read(saved.documentJson()).has("backgroundMusic")).isFalse();
        assertThat(persistence.saveBrief(task,"default","legacy-no-music",input).briefId()).isEqualTo(saved.briefId());
        assertThat(revision(task)).isEqualTo(1);
    }

    @Test
    void musicMustBeSelectedReadyMusicAndFreezesWithHistoricalReferences() {
        String task=task("default");String wrong=asset(task,"default","图片.png","a");
        String music=audioAsset(task,"MUSIC",8000,"b");
        var settings=new BackgroundMusic(music,1000,6000,cny("-18"),500,500,true);
        conflict(()->persistence.saveBrief(task,"default","missing-selection",new BriefInput(0L,"音乐演示","用户材料","新手",5000,"NO_DIALOGUE","USER_PROVIDED",List.of(),"",settings)));
        conflict(()->persistence.saveBrief(task,"default","wrong-kind",new BriefInput(0L,"音乐演示","用户材料","新手",5000,"NO_DIALOGUE","USER_PROVIDED",List.of(wrong),"",new BackgroundMusic(wrong,0,5000,cny("-18"),0,0,false))));
        conflict(()->persistence.saveBrief(task,"default","wrong-range",new BriefInput(0L,"音乐演示","用户材料","新手",5000,"NO_DIALOGUE","USER_PROVIDED",List.of(music),"",new BackgroundMusic(music,6000,1000,cny("-18"),0,0,false))));
        assertThat(count("creator_creation_brief",task)).isZero();assertThat(revision(task)).isZero();
        var brief=persistence.saveBrief(task,"default","music-brief",new BriefInput(0L,"音乐演示","用户材料","新手",5000,"NO_DIALOGUE","USER_PROVIDED",List.of(music),"",settings));
        var board=persistence.save(task,"default","music-board",new Save(revision(task),brief.briefId(),null,candidate(null,List.of("brief:user"),List.of())));
        var compiler=new StoryboardCompiler(JSON);
        var checked=compiler.check(board.storyboardId(),codec.read(brief.documentJson()),codec.candidate(codec.read(board.documentJson()).path("candidate")),persistence.frozen(board));
        assertThat(checked.renderable()).isTrue();
        assertThat(mapper.references(task,"default",music,0,20)).extracting(StoryboardMapper.Reference::storyboardId).containsExactly(board.storyboardId());
        assertThat(codec.read(brief.documentJson()).path("backgroundMusic").path("sourceInMs").asLong()).isEqualTo(1000);
        assertThat(count("creator_creation_run",task)).isZero();assertThat(count("creator_creation_cost_entry",task)).isZero();
    }

    @Test
    void importedSrtCreatesImmutableChildAndOriginalRequestIdempotencyWithoutPaidRun() {
        String task=task("default");String voice=audioAsset(task,"NARRATION",3000,"c");
        var brief=persistence.saveBrief(task,"default","voice-brief",new BriefInput(0L,"录音字幕","用户自己的录音","新手",5000,"USER_NARRATION","USER_PROVIDED",List.of(voice),""));
        var original=new Candidate(List.of(new Scene("opening","TITLE_CARD","用户录音",List.of(),"录音文稿",5000,null,null,null,"CONTAIN","NARRATION",voice,List.of(),List.of("brief:user"))));
        var parent=persistence.save(task,"default","voice-board",new Save(revision(task),brief.briefId(),null,original));
        var audio=new StoryboardAudioService(mapper,persistence,codec,new StoryboardCompiler(JSON),media,JSON);
        String srt="\uFEFF1\r\n00:00:00,100 --> 00:00:02,900\r\n用户提供的真实时间码\r\n";
        var input=new SubtitleImport(revision(task),"opening",srt);long before=revision(task);
        var child=audio.importSubtitles(task,"default","user-srt",parent.storyboardId(),input);
        assertThat(child.parentStoryboardId()).isEqualTo(parent.storyboardId());assertThat(child.runId()).isNull();
        assertThat(codec.read(child.documentJson()).path("subtitleTimingSource").asText()).isEqualTo("USER_PROVIDED");
        assertThat(codec.candidate(codec.read(parent.documentJson()).path("candidate")).scenes().getFirst().cues()).isEmpty();
        assertThat(codec.candidate(codec.read(child.documentJson()).path("candidate")).scenes().getFirst().cues()).containsExactly(new Cue(100,2900,"用户提供的真实时间码"));
        assertThat(revision(task)).isEqualTo(before+1);
        assertThat(audio.importSubtitles(task,"default","user-srt",parent.storyboardId(),input).storyboardId()).isEqualTo(child.storyboardId());
        conflict(()->audio.importSubtitles(task,"default","user-srt",parent.storyboardId(),new SubtitleImport(input.expectedWorkspaceRevision(),"opening",srt.replace("\r\n","\n"))));
        conflict(()->audio.importSubtitles(task,"default","stale-import",parent.storyboardId(),input));
        conflict(()->audio.importSubtitles(task,"default","past-audio",child.storyboardId(),new SubtitleImport(revision(task),"opening","1\n00:00:00,100 --> 00:00:03,100\n超出实际录音")));
        assertThat(count("creator_creation_storyboard",task)).isEqualTo(2);assertThat(count("creator_creation_run",task)).isZero();
        assertThat(count("creator_creation_cost_entry",task)).isZero();assertThat(revision(task)).isEqualTo(before+1);
    }

    @Test
    void timingSuggestionsUseStoredAudioFactsAndNeverApplyChanges() {
        String task=task("default");String voice=audioAsset(task,"NARRATION",3000,"d");
        var brief=persistence.saveBrief(task,"default","brief",new BriefInput(0L,"时长建议","完整保留录音","新手",2000,"USER_NARRATION","USER_PROVIDED",List.of(voice),""));
        var candidate=new Candidate(List.of(new Scene("opening","TITLE_CARD","录音过长",List.of(),"录音文稿",2000,null,null,null,"CONTAIN","NARRATION",voice,List.of(),List.of("brief:user"))));
        var board=persistence.save(task,"default","board",new Save(revision(task),brief.briefId(),null,candidate));
        long before=revision(task);var audio=new StoryboardAudioService(mapper,persistence,codec,new StoryboardCompiler(JSON),media,JSON);
        var report=audio.timing(task,"default",board.storyboardId());
        assertThat(report.requiresUserDecision()).isTrue();assertThat(report.scenes().getFirst().audioDurationMs()).isEqualTo(3000L);
        assertThat(report.proposals()).anySatisfy(p->{assertThat(p.code()).isEqualTo("EXTEND_SCENE");assertThat(p.proposedDurationMs()).isEqualTo(3000L);});
        assertThat(revision(task)).isEqualTo(before);assertThat(count("creator_creation_storyboard",task)).isEqualTo(1);
        assertThat(mapper.board(task,"default",board.storyboardId()).orElseThrow().documentJson()).isEqualTo(board.documentJson());
        assertThat(count("creator_creation_run",task)).isZero();
    }

    @Test
    void insufficientMusicProducesReadOnlyTimingProposalInsteadOfLooping() {
        String task=task("default");String music=audioAsset(task,"MUSIC",2000,"e");
        var settings=new BackgroundMusic(music,0,5000,cny("-18"),0,0,false);
        var brief=persistence.saveBrief(task,"default","music-too-short",new BriefInput(0L,"音乐时长不足","用户素材","新手",5000,"NO_DIALOGUE","USER_PROVIDED",List.of(music),"",settings));
        var board=persistence.save(task,"default","board",new Save(revision(task),brief.briefId(),null,candidate(null,List.of("brief:user"),List.of())));
        var audio=new StoryboardAudioService(mapper,persistence,codec,new StoryboardCompiler(JSON),media,JSON);long before=revision(task);
        var report=audio.timing(task,"default",board.storyboardId());
        assertThat(report.requiresUserDecision()).isTrue();assertThat(report.proposals()).extracting(p->p.code()).contains("BGM_OUT_OF_RANGE");
        assertThat(revision(task)).isEqualTo(before);assertThat(count("creator_creation_storyboard",task)).isEqualTo(1);
        assertThat(codec.read(persistence.brief(task,"default",brief.briefId()).documentJson()).path("backgroundMusic").path("sourceOutMs").asLong()).isEqualTo(5000);
    }

    @Test
    void extendingNarrationNeedsEnoughRealVideoInsteadOfImpossibleDurationAdvice() {
        String task=task("default");String voice=audioAsset(task,"NARRATION",3000,"f");String video=asset(task,"default","short.mp4","1");
        var metadata=JSON.createObjectNode();metadata.putObject("normalized").putArray("streams").addObject()
                .put("codec_type","video").put("duration",2).put("start_time",0);
        jdbc.update("UPDATE creator_creation_asset SET kind='VIDEO_CLIP',metadata_json=? WHERE asset_id=?",codec.write(metadata),video);
        var brief=persistence.saveBrief(task,"default","brief",new BriefInput(0L,"短视频配录音","保留完整录音","新手",2000,"USER_NARRATION","USER_PROVIDED",List.of(voice,video),""));
        var candidate=new Candidate(List.of(new Scene("opening","VIDEO_CLIP","短素材",List.of(),"录音文稿",2000,video,0L,2000L,"CONTAIN","NARRATION",voice,List.of(),List.of("brief:user"))));
        var board=persistence.save(task,"default","board",new Save(revision(task),brief.briefId(),null,candidate));long before=revision(task);
        var audio=new StoryboardAudioService(mapper,persistence,codec,new StoryboardCompiler(JSON),media,JSON);
        var report=audio.timing(task,"default",board.storyboardId());
        assertThat(report.proposals()).extracting(p->p.code()).contains("SUPPLY_MORE_VIDEO","RECORD_AGAIN").doesNotContain("EXTEND_SCENE");
        assertThat(revision(task)).isEqualTo(before);assertThat(count("creator_creation_storyboard",task)).isEqualTo(1);
    }

    @Test
    void staleWorkspaceCannotCreateAnyBriefOrRun() {
        String task = task("default");
        var first = persistence.saveBrief(task, "default", "first", brief(0, List.of()));
        conflict(() -> persistence.saveBrief(task, "default", "stale", brief(0, List.of())));
        initialize(task);
        conflict(() -> persistence.generate(task, "default", "stale", new Generate(0L, first.briefId(), cny("1"))));
        assertThat(count("creator_creation_brief", task)).isEqualTo(1);
        assertThat(count("creator_creation_run", task)).isZero();
        assertThat(count("creator_creation_approval", task)).isZero();
    }

    @Test
    void activePreparationBlocksBriefAndManualBoardUntilCancelled() {
        String task = task("default");
        var brief = persistence.saveBrief(task, "default", "brief", brief(0, List.of()));
        initialize(task);
        var job = persistence.generate(task, "default", "prepare", new Generate(revision(task), brief.briefId(), cny("2")));
        long activeRevision = revision(task);
        conflict(() -> persistence.saveBrief(task, "default", "new-brief", brief(activeRevision, List.of())));
        conflict(() -> persistence.save(task, "default", "manual", new Save(activeRevision, brief.briefId(), null, candidate(null, List.of("brief:user"), List.of()))));
        runtime.cancel(task, "default", job.runId());
        var accepted = persistence.saveBrief(task, "default", "new-brief", brief(revision(task), List.of()));
        assertThat(accepted.revision()).isEqualTo(2);
        assertThat(count("creator_creation_storyboard", task)).isZero();
    }

    @Test
    void anyBindingFactBlocksNewBriefBoardAndPreparation() {
        String task = task("default");
        var brief = persistence.saveBrief(task, "default", "brief", brief(0, List.of()));
        initialize(task);
        // 即使还在核验，也已经进入发布后链路，不能从故事板入口绕回付费制作。
        jdbc.update("INSERT INTO creator_task_video_binding(binding_id,task_id,user_id,bvid,binding_status) VALUES (?,?,'default','BV1TEST000001','WAITING_VERIFY')", UUID.randomUUID().toString(), task);
        conflict(() -> persistence.saveBrief(task, "default", "later", brief(revision(task), List.of())));
        conflict(() -> persistence.save(task, "default", "manual", new Save(revision(task), brief.briefId(), null, candidate(null, List.of("brief:user"), List.of()))));
        conflict(() -> persistence.generate(task, "default", "prepare", new Generate(revision(task), brief.briefId(), cny("2"))));
        assertThat(count("creator_creation_approval", task)).isZero();
        assertThat(count("creator_creation_run", task)).isZero();
        assertThat(count("creator_creation_storyboard", task)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"UPLOADING", "PREPARING", "FAILED", "CANCELLED"})
    void onlyReadyAssetCanEnterBrief(String state) {
        String task = task("default");
        String asset = asset(task, "default", "same-name.png", "a");
        jdbc.update("UPDATE creator_creation_asset SET status=? WHERE asset_id=?", state, asset);
        conflict(() -> persistence.saveBrief(task, "default", "brief", brief(0, List.of(asset))));
        assertThat(count("creator_creation_brief", task)).isZero();
        assertThat(revision(task)).isZero();
    }

    @Test
    void foreignAssetMissingNormalizedFileAndDuplicateSelectionAreRejected() {
        String task = task("default");
        String other = task("other");
        String foreign = asset(other, "other", "same-name.png", "a");
        rejected(() -> persistence.saveBrief(task, "default", "foreign", brief(0, List.of(foreign))), 404);
        String sameOwnerOtherTask = task("default");
        String outsideTask = asset(sameOwnerOtherTask, "default", "same-name.png", "a");
        rejected(() -> persistence.saveBrief(task, "default", "foreign-task", brief(0, List.of(outsideTask))), 404);
        String own = asset(task, "default", "same-name.png", "b");
        conflict(() -> persistence.saveBrief(task, "default", "duplicate", brief(0, List.of(own, own))));
        jdbc.update("UPDATE creator_creation_asset SET objects_json='[]' WHERE asset_id=?", own);
        conflict(() -> persistence.saveBrief(task, "default", "missing-work-file", brief(0, List.of(own))));
        assertThat(count("creator_creation_brief", task)).isZero();
    }

    @Test
    void sameFilenameDoesNotRetargetFrozenAssetAndReferencesRemainQueryable() {
        String task = task("default");
        String first = asset(task, "default", "same-name.png", "a");
        var brief = persistence.saveBrief(task, "default", "brief", brief(0, List.of(first)));
        var board = persistence.save(task, "default", "manual", new Save(revision(task), brief.briefId(), null, candidate(first, List.of("brief:user"), List.of())));
        String replacement = asset(task, "default", "same-name.png", "b");
        assertThat(replacement).isNotEqualTo(first);
        var frozen = persistence.frozen(mapper.board(task, "default", board.storyboardId()).orElseThrow());
        assertThat(frozen).hasSize(1);
        assertThat(frozen.getFirst().assetId()).isEqualTo(first);
        assertThat(frozen.getFirst().sha256()).isEqualTo("a".repeat(64));
        assertThat(frozen.getFirst().objectKey()).contains(first).doesNotContain(replacement);
        assertThat(frozen.getFirst().metadata().path("width").asInt()).isEqualTo(640);
        assertThat(mapper.references(task, "default", first, 0, 20))
                .extracting(StoryboardMapper.Reference::storyboardId).containsExactly(board.storyboardId());
        assertThat(mapper.references(task, "default", replacement, 0, 20)).isEmpty();
        assertThat(mapper.references(task, "other", first, 0, 20)).isEmpty();
        assertThat(board.assetSnapshotJson()).doesNotContain("https://", "signature", "accessKey");
    }

    @Test
    void manualEditsCreateChildVersionAndCannotBorrowAnotherTasksParent() {
        String task = task("default");
        var brief = persistence.saveBrief(task, "default", "brief", brief(0, List.of()));
        var firstInput = new Save(revision(task), brief.briefId(), null, candidate(null, List.of("brief:user"), List.of()));
        var first = persistence.save(task, "default", "first", firstInput);
        long afterFirst = revision(task);
        assertThat(persistence.save(task, "default", "first", firstInput).storyboardId()).isEqualTo(first.storyboardId());
        assertThat(revision(task)).isEqualTo(afterFirst);
        var child = persistence.save(task, "default", "second", new Save(afterFirst, brief.briefId(), first.storyboardId(), candidate(null, List.of("brief:user"), List.of())));
        assertThat(child.parentStoryboardId()).isEqualTo(first.storyboardId());
        assertThat(child.revision()).isEqualTo(2);
        assertThat(child.storyboardId()).isNotEqualTo(first.storyboardId());
        assertThat(mapper.board(task, "default", first.storyboardId()).orElseThrow().documentHash()).isEqualTo(first.documentHash());
        String another = task("default");
        var anotherBrief = persistence.saveBrief(another, "default", "brief", brief(0, List.of()));
        conflict(() -> persistence.save(another, "default", "foreign-parent", new Save(revision(another), anotherBrief.briefId(), first.storyboardId(), candidate(null, List.of("brief:user"), List.of()))));
        assertThat(count("creator_creation_storyboard", another)).isZero();
    }

    @Test
    void updatedBriefRejectsOldBriefBoardAndPreparationWithoutChangingHistory() {
        String task = task("default");
        var first = persistence.saveBrief(task, "default", "first", brief(0, List.of()));
        persistence.saveBrief(task, "default", "second", brief(revision(task), List.of()));
        initialize(task);
        conflict(() -> persistence.save(task, "default", "stale", new Save(revision(task), first.briefId(), null, candidate(null, List.of("brief:user"), List.of()))));
        conflict(() -> persistence.generate(task, "default", "stale", new Generate(revision(task), first.briefId(), cny("1"))));
        assertThat(count("creator_creation_storyboard", task)).isZero();
        assertThat(count("creator_creation_run", task)).isZero();
    }

    @Test
    void factsUseRealMaterialSourceIdsAndInventedSourceIsRejected() {
        String task = task("default");
        String sourceId = material(task, "用户确认的文稿");
        var brief = persistence.saveBrief(task, "default", "brief", brief(0, List.of()));
        conflict(() -> persistence.save(task, "default", "invented", new Save(revision(task), brief.briefId(), null, candidate(null, List.of("material:invented"), List.of()))));
        conflict(() -> persistence.save(task, "default", "missing", new Save(revision(task), brief.briefId(), null, candidate(null, List.of(), List.of()))));
        var board = persistence.save(task, "default", "real", new Save(revision(task), brief.briefId(), null, candidate(null, List.of(sourceId), List.of())));
        assertThat(codec.read(board.documentJson()).path("candidate").path("scenes").get(0).path("sourceRefs").get(0).asText()).isEqualTo(sourceId);
        assertThat(count("creator_creation_storyboard", task)).isEqualTo(1);
    }

    @Test
    void modelCannotInventSubtitleTimesButUserProvidedCuesCanBeSaved() {
        String task = task("default");
        var brief = persistence.saveBrief(task, "default", "brief", brief(0, List.of()));
        var supplied = candidate(null, List.of("brief:user"), List.of(new Cue(0, 1000, "用户对齐字幕")));
        var manual = persistence.save(task, "default", "manual", new Save(revision(task), brief.briefId(), null, supplied));
        assertThat(codec.read(manual.documentJson()).path("subtitleTimingSource").asText()).isEqualTo("USER_PROVIDED");
        initialize(task);
        var job = persistence.generate(task, "default", "prepare", new Generate(revision(task), brief.briefId(), cny("1")));
        var lease = claim(job, "storyboard-worker");
        conflict(() -> persistence.finish(job, lease, supplied, List.of()));
        assertThat(count("creator_creation_storyboard", task)).isEqualTo(1);
        assertThat(mapper.job(task, "default", job.runId()).orElseThrow().storyboardId()).isNull();
        assertThat(runs.run(task, "default", job.runId()).orElseThrow().status()).isEqualTo("RUNNING");
    }

    @Test
    void preparationRequiresBudgetAndSameKeyNeverCreatesAdditionalApprovalOrRun() {
        String task = task("default");
        var brief = persistence.saveBrief(task, "default", "brief", brief(0, List.of()));
        conflict(() -> persistence.generate(task, "default", "no-budget", new Generate(revision(task), brief.briefId(), cny("1"))));
        assertThat(count("creator_creation_approval", task)).isZero();
        initialize(task);
        var input = new Generate(revision(task), brief.briefId(), cny("2"));
        var first = persistence.generate(task, "default", "prepare", input);
        long after = revision(task);
        assertThat(persistence.generate(task, "default", "prepare", input).runId()).isEqualTo(first.runId());
        conflict(() -> persistence.generate(task, "default", "prepare", new Generate(input.expectedWorkspaceRevision(), brief.briefId(), cny("3"))));
        assertThat(revision(task)).isEqualTo(after);
        assertThat(count("creator_creation_approval", task)).isEqualTo(1);
        assertThat(count("creator_creation_run", task)).isEqualTo(1);
        assertThat(count("creator_creation_storyboard_job", task)).isEqualTo(1);
        var run = runs.run(task, "default", first.runId()).orElseThrow();
        assertThat(run.kind()).isEqualTo("PREPARE");
        assertThat(run.status()).isEqualTo("QUEUED");
        var frozen = codec.read(run.requestJson());
        assertThat(frozen.path("price").path("model").asText()).isEqualTo("configured-test-text-model");
        assertThat(frozen.path("price").path("basis").asText()).isEqualTo("CONFIGURED_ESTIMATE");
        assertThat(frozen.path("context").path("brief").path("topic").asText()).isEqualTo("真实素材与分镜测试");
        assertThat(runtime.snapshot(task, "default").executionAvailable()).isFalse();
        verify(generator, never()).generate(anyString(), anyString(), anyInt());
    }

    @Test
    void preparationEstimateIsReadOnlyUsesConfiguredUpperBoundAndHidesStorageIdentity() {
        String task = task("default");
        String asset = asset(task, "default", "same-name.png", "a");
        material(task, "只读估价必须包含本次实际输入的中文材料");
        var brief = persistence.saveBrief(task, "default", "brief", brief(0, List.of(asset)));
        initialize(task);
        long expected = revision(task);
        var input = new EstimateInput(expected, brief.briefId());
        var estimate = persistence.estimate(task, "default", input);
        assertThat(persistence.estimate(task, "default", input)).isEqualTo(estimate);
        var value = codec.read(codec.write(estimate));
        assertThat(value.path("workspaceRevision").asLong()).isEqualTo(expected);
        assertThat(value.path("maxOutputTokens").asInt()).isEqualTo(4096);
        assertThat(value.path("inputTokenUpperBound").asLong()).isGreaterThan(4096);
        assertThat(value.path("model").asText()).isEqualTo("configured-test-text-model");
        assertThat(value.path("basis").asText()).isEqualTo("CONFIGURED_ESTIMATE");
        var expectedCost = prices.getTextInputUsdPerMillionTokens().multiply(BigDecimal.valueOf(value.path("inputTokenUpperBound").asLong()))
                .add(prices.getTextOutputUsdPerMillionTokens().multiply(new BigDecimal("4096")))
                .multiply(prices.getUsdToCny()).divide(new BigDecimal("1000000"), 6, RoundingMode.CEILING);
        assertThat(value.path("estimatedMaxAdditionalCostCny").decimalValue()).isEqualByComparingTo(expectedCost);
        assertThat(codec.write(estimate)).doesNotContain("bucketName", "objectKey", "isolated-storyboard", "users/default/", "signature");
        assertThat(revision(task)).isEqualTo(expected);
        assertThat(runs.workspace(task, "default").orElseThrow().activeRunId()).isNull();
        assertThat(count("creator_creation_approval", task)).isZero();
        assertThat(count("creator_creation_run", task)).isZero();
        assertThat(count("creator_creation_storyboard_job", task)).isZero();
        assertThat(count("creator_creation_cost_entry", task)).isZero();
        assertThat(count("creator_creation_brief", task)).isEqualTo(1);
        assertThat(count("creator_creation_storyboard", task)).isZero();
        verify(generator, never()).generate(anyString(), anyString(), anyInt());
    }

    @Test
    void authorizationBelowEstimatedCostRejectsWithoutApprovalRunOrJob() {
        String task = task("default");
        var brief = persistence.saveBrief(task, "default", "brief", brief(0, List.of()));
        initialize(task);
        long expected = revision(task);
        var estimate = codec.read(codec.write(persistence.estimate(task, "default", new EstimateInput(expected, brief.briefId()))));
        var reservation = estimate.path("estimatedMaxAdditionalCostCny").decimalValue();
        assertThat(reservation).isGreaterThan(cny("0.000001"));
        conflict(() -> persistence.generate(task, "default", "insufficient-confirmation",
                new Generate(expected, brief.briefId(), cny("0.000001"))));
        assertThat(revision(task)).isEqualTo(expected);
        assertThat(runs.workspace(task, "default").orElseThrow().activeRunId()).isNull();
        assertThat(count("creator_creation_approval", task)).isZero();
        assertThat(count("creator_creation_run", task)).isZero();
        assertThat(count("creator_creation_storyboard_job", task)).isZero();
        assertThat(count("creator_creation_cost_entry", task)).isZero();
        verify(generator, never()).generate(anyString(), anyString(), anyInt());
        var accepted = persistence.generate(task, "default", "exact-confirmation", new Generate(expected, brief.briefId(), reservation));
        var queued = runs.run(task, "default", accepted.runId()).orElseThrow();
        assertThat(codec.read(queued.requestJson()).path("price").path("reservationCny").decimalValue()).isEqualByComparingTo(reservation);
        assertThat(codec.read(queued.requestJson()).path("price").path("inputTokenUpperBound").asLong())
                .isEqualTo(estimate.path("inputTokenUpperBound").asLong());
        assertThat(count("creator_creation_approval", task)).isEqualTo(1);
        assertThat(count("creator_creation_run", task)).isEqualTo(1);
        assertThat(count("creator_creation_storyboard_job", task)).isEqualTo(1);
    }

    @Test
    void jobAdmissionFailureRollsBackBudgetApprovalRunAndWorkspaceActivation() {
        String task = task("default");
        var brief = persistence.saveBrief(task, "default", "brief", brief(0, List.of()));
        initialize(task);
        long expected = revision(task);
        var failingMapper = spy(mapper);
        doThrow(new IllegalStateException("受控任务写入失败")).when(failingMapper).insertJob(any(Job.class));
        var failing = service(failingMapper);
        assertThatThrownBy(() -> failing.generate(task, "default", "prepare", new Generate(expected, brief.briefId(), cny("2"))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("受控任务写入失败");
        assertThat(count("creator_creation_approval", task)).isZero();
        assertThat(count("creator_creation_run", task)).isZero();
        assertThat(count("creator_creation_storyboard_job", task)).isZero();
        assertThat(revision(task)).isEqualTo(expected);
        assertThat(runs.workspace(task, "default").orElseThrow().activeRunId()).isNull();
        assertThat(runtime.snapshot(task, "default").budget().limitCny()).isEqualByComparingTo("20");
    }

    @Test
    void finishIsAtomicAndOnlyCompletesAfterFeeSettled() {
        String task = task("default");
        String asset = asset(task, "default", "same-name.png", "a");
        var brief = persistence.saveBrief(task, "default", "brief", brief(0, List.of(asset)));
        initialize(task);
        var job = persistence.generate(task, "default", "prepare", new Generate(revision(task), brief.briefId(), cny("2")));
        var lease = claim(job, "storyboard-worker");
        String charge = "storyboard-" + job.runId();
        runtime.reserve(task, "default", job.runId(), lease.leaseOwner(), lease.leaseGeneration(), charge, "TEXT", cny("0.2"), "{\"model\":\"fixture\"}");
        assertThat(runtime.markSent(task, "default", charge, lease.leaseOwner(), lease.leaseGeneration())).isTrue();
        var candidate = candidate(asset, List.of("brief:user"), List.of());
        var frozen = persistence.snapshotAssets(task, "default", List.of(asset));
        conflict(() -> persistence.finish(job, lease, candidate, frozen));
        assertThat(count("creator_creation_storyboard", task)).isZero();
        assertThat(count("creator_creation_storyboard_asset", task)).isZero();
        assertThat(mapper.job(task, "default", job.runId()).orElseThrow().storyboardId()).isNull();
        assertThat(runs.workspace(task, "default").orElseThrow().activeRunId()).isEqualTo(job.runId());
        assertThat(runs.run(task, "default", job.runId()).orElseThrow().status()).isEqualTo("RUNNING");
        runtime.settle(task, "default", charge, cny("0.1"), "USAGE_ESTIMATE", "fixture:usage");
        var board = persistence.finish(job, lease, candidate, frozen);
        assertThat(board.runId()).isEqualTo(job.runId());
        assertThat(board.revision()).isEqualTo(1);
        assertThat(mapper.job(task, "default", job.runId()).orElseThrow().storyboardId()).isEqualTo(board.storyboardId());
        assertThat(runs.workspace(task, "default").orElseThrow().activeRunId()).isNull();
        var completed = runs.run(task, "default", job.runId()).orElseThrow();
        assertThat(completed.status()).isEqualTo("COMPLETED");
        assertThat(codec.read(completed.resultJson()).path("storyboardId").asText()).isEqualTo(board.storyboardId());
        assertThat(codec.read(board.documentJson()).path("subtitleTimingSource").asText()).isEqualTo("NOT_PROVIDED");
        assertThat(runtime.findCharge(task, "default", job.runId(), charge).orElseThrow().status()).isEqualTo("SETTLED");
        assertThat(runtime.snapshot(task, "default").budget().knownCny()).isEqualByComparingTo("0.1");
        assertThat(runtime.snapshot(task, "default").budget().reservedCny()).isZero();
        assertThat(persistence.finish(job, lease, candidate, frozen).storyboardId()).isEqualTo(board.storyboardId());
        assertThat(count("creator_creation_storyboard", task)).isEqualTo(1);
        assertThat(mapper.references(task, "default", asset, 0, 20)).hasSize(1);
    }

    @Test
    void expiredOrReplacedLeaseCannotSaveCandidate() {
        String task = task("default");
        var brief = persistence.saveBrief(task, "default", "brief", brief(0, List.of()));
        initialize(task);
        var job = persistence.generate(task, "default", "prepare", new Generate(revision(task), brief.briefId(), cny("1")));
        var old = claim(job, "old-worker");
        assertThat(runtime.claim(task, "default", job.runId(), "new-worker", 30)).isEmpty();
        jdbc.update("UPDATE creator_creation_run SET lease_expires_at=TIMESTAMPADD(SECOND,-1,NOW(6)) WHERE run_id=?", job.runId());
        conflict(() -> persistence.finish(job, old, candidate(null, List.of("brief:user"), List.of()), List.of()));
        var replacement = claim(job, "new-worker");
        assertThat(replacement.leaseGeneration()).isEqualTo(old.leaseGeneration() + 1);
        conflict(() -> persistence.finish(job, old, candidate(null, List.of("brief:user"), List.of()), List.of()));
        assertThat(count("creator_creation_storyboard", task)).isZero();
        assertThat(mapper.job(task, "default", job.runId()).orElseThrow().storyboardId()).isNull();
        assertThat(runs.workspace(task, "default").orElseThrow().activeRunId()).isEqualTo(job.runId());
        var saved = persistence.finish(job, replacement, candidate(null, List.of("brief:user"), List.of()), List.of());
        assertThat(saved.runId()).isEqualTo(job.runId());
    }

    @Test
    void cancellationBeforeSavePreservesHistoryAndRejectsLateCandidate() {
        String task = task("default");
        var brief = persistence.saveBrief(task, "default", "brief", brief(0, List.of()));
        initialize(task);
        var job = persistence.generate(task, "default", "prepare", new Generate(revision(task), brief.briefId(), cny("1")));
        var lease = claim(job, "storyboard-worker");
        assertThat(runtime.cancel(task, "default", job.runId()).status()).isEqualTo("CANCEL_REQUESTED");
        conflict(() -> persistence.finish(job, lease, candidate(null, List.of("brief:user"), List.of()), List.of()));
        assertThat(count("creator_creation_storyboard", task)).isZero();
        assertThat(mapper.job(task, "default", job.runId()).orElseThrow().storyboardId()).isNull();
        runtime.acknowledgeCancellation(task, "default", job.runId(), lease.leaseOwner(), lease.leaseGeneration());
        assertThat(runs.run(task, "default", job.runId()).orElseThrow().status()).isEqualTo("CANCELLED");
        assertThat(runs.workspace(task, "default").orElseThrow().activeRunId()).isNull();
    }

    @Test
    void unknownFeeBlocksNewPhysicalCallEvenAfterNewPreparationWasConfirmed() {
        String task = task("default");
        var brief = persistence.saveBrief(task, "default", "brief", brief(0, List.of()));
        initialize(task);
        var first = persistence.generate(task, "default", "first", new Generate(revision(task), brief.briefId(), cny("2")));
        var lease = claim(first, "storyboard-worker");
        runtime.reserve(task, "default", first.runId(), lease.leaseOwner(), lease.leaseGeneration(), "unknown-first", "TEXT", cny("0.2"), "{\"basis\":\"fixture\"}");
        runtime.markSent(task, "default", "unknown-first", lease.leaseOwner(), lease.leaseGeneration());
        runtime.markUnknown(task, "default", "unknown-first");
        runtime.fail(task, "default", first.runId(), lease.leaseOwner(), lease.leaseGeneration(), "SUPPLIER_FAILED");
        var second = persistence.generate(task, "default", "second", new Generate(revision(task), brief.briefId(), cny("2")));
        var newLease = claim(second, "other-worker");
        conflict(() -> runtime.reserve(task, "default", second.runId(), newLease.leaseOwner(), newLease.leaseGeneration(), "must-not-send", "TEXT", cny("0.2"), "{\"basis\":\"fixture\"}"));
        assertThat(runtime.findCharge(task, "default", second.runId(), "must-not-send")).isEmpty();
        assertThat(runtime.snapshot(task, "default").budget().unknownCny()).isEqualByComparingTo("0.2");
        assertThat(runtime.snapshot(task, "default").budget().paidCallsBlocked()).isTrue();
        verify(generator, never()).generate(anyString(), anyString(), anyInt());
    }

    @Test
    void workerSuccessUsesQueuedPricesAndSendsOnlySafeAssetCatalogToTextModel() {
        String task = task("default");
        String asset = asset(task, "default", "same-name.png", "a");
        String materialId = material(task, "模型只能引用这份真实材料");
        var brief = persistence.saveBrief(task, "default", "brief", brief(0, List.of(asset)));
        initialize(task);
        var job = persistence.generate(task, "default", "prepare", new Generate(revision(task), brief.briefId(), cny("2")));
        // 排队后变更部署价格，已确认运行仍按入队快照结算，不能重新定价旧调用。
        prices.setUsdToCny(cny("9"));
        prices.setTextInputUsdPerMillionTokens(cny("10"));
        prices.setTextOutputUsdPerMillionTokens(cny("30"));
        when(generator.generate(anyString(), anyString(), eq(4096))).thenReturn(
                new StoryboardTextGenerator.Raw(codec.write(candidate(asset, List.of(materialId), List.of())), 1000, 500, "configured-test-text-model"));
        try (var worker = worker()) {
            worker.process(job);
        }
        var completed = runs.run(task, "default", job.runId()).orElseThrow();
        assertThat(completed.status()).isEqualTo("COMPLETED");
        assertThat(runs.workspace(task, "default").orElseThrow().activeRunId()).isNull();
        var charge = runtime.findCharge(task, "default", job.runId(), "storyboard:" + job.runId()).orElseThrow();
        assertThat(charge.status()).isEqualTo("SETTLED");
        assertThat(charge.knownCostCny()).isEqualByComparingTo("0.004320");
        var queuedPrice = codec.read(completed.requestJson()).path("price");
        assertThat(charge.reservedCny()).isEqualByComparingTo(queuedPrice.path("reservationCny").decimalValue());
        assertThat(charge.reservedCny()).isLessThan(completed.authorizationLimitCny());
        assertThat(codec.read(charge.priceSnapshotJson()).path("usdToCny").decimalValue()).isEqualByComparingTo("7.20");
        assertThat(codec.read(charge.priceSnapshotJson()).path("inputUsdPerMillion").decimalValue()).isEqualByComparingTo("0.20");
        assertThat(codec.read(charge.priceSnapshotJson()).path("outputUsdPerMillion").decimalValue()).isEqualByComparingTo("0.80");
        var savedJob = mapper.job(task, "default", job.runId()).orElseThrow();
        assertThat(savedJob.storyboardId()).isNotNull();
        var board = mapper.board(task, "default", savedJob.storyboardId()).orElseThrow();
        assertThat(persistence.frozen(board)).extracting(Asset::assetId).containsExactly(asset);
        var prompt = ArgumentCaptor.forClass(String.class);
        verify(generator, times(1)).generate(anyString(), prompt.capture(), eq(4096));
        assertThat(prompt.getValue()).doesNotContain("bucketName", "objectKey", "isolated-storyboard", "users/default/", "signature");
        assertThat(codec.read(prompt.getValue()).path("assets").get(0).path("assetId").asText()).isEqualTo(asset);
        assertThat(codec.read(prompt.getValue()).path("sources").path("materials").get(0).path("sourceId").asText()).isEqualTo(materialId);
        assertThat(count("creator_creation_storyboard", task)).isEqualTo(1);
    }

    @Test
    void malformedModelJsonStillSettlesReportedUsageButSavesNoCandidate() {
        String task = task("default");
        var brief = persistence.saveBrief(task, "default", "brief", brief(0, List.of()));
        initialize(task);
        var job = persistence.generate(task, "default", "prepare", new Generate(revision(task), brief.briefId(), cny("2")));
        when(generator.generate(anyString(), anyString(), eq(4096))).thenReturn(
                new StoryboardTextGenerator.Raw("{坏 JSON", 1000, 500, "configured-test-text-model"));
        try (var worker = worker()) {
            worker.process(job);
        }
        var failed = runs.run(task, "default", job.runId()).orElseThrow();
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.errorCode()).isEqualTo("STORYBOARD_PREPARATION_FAILED");
        assertThat(runs.workspace(task, "default").orElseThrow().activeRunId()).isNull();
        var charge = runtime.findCharge(task, "default", job.runId(), "storyboard:" + job.runId()).orElseThrow();
        assertThat(charge.status()).isEqualTo("SETTLED");
        assertThat(charge.knownCostCny()).isEqualByComparingTo("0.004320");
        assertThat(count("creator_creation_storyboard", task)).isZero();
        assertThat(mapper.job(task, "default", job.runId()).orElseThrow().storyboardId()).isNull();
        verify(generator, times(1)).generate(anyString(), anyString(), eq(4096));
    }

    @Test
    void missingOneUsageSideKeepsValidCandidateAndUnknownFeeBlocksNextModelCall() {
        String task = task("default");
        var brief = persistence.saveBrief(task, "default", "brief", brief(0, List.of()));
        initialize(task);
        var job = persistence.generate(task, "default", "prepare", new Generate(revision(task), brief.briefId(), cny("2")));
        when(generator.generate(anyString(), anyString(), eq(4096))).thenReturn(
                new StoryboardTextGenerator.Raw(codec.write(candidate(null, List.of("brief:user"), List.of())), null, 500, "configured-test-text-model"));
        try (var worker = worker()) {
            worker.process(job);
        }
        assertThat(runs.run(task, "default", job.runId()).orElseThrow().status()).isEqualTo("COMPLETED");
        assertThat(mapper.job(task, "default", job.runId()).orElseThrow().storyboardId()).isNotNull();
        var charge = runtime.findCharge(task, "default", job.runId(), "storyboard:" + job.runId()).orElseThrow();
        assertThat(charge.status()).isEqualTo("UNKNOWN");
        assertThat(charge.knownCostCny()).isNull();
        var reservation = codec.read(runs.run(task, "default", job.runId()).orElseThrow().requestJson()).path("price").path("reservationCny").decimalValue();
        assertThat(reservation).isPositive().isLessThan(cny("2"));
        assertThat(charge.reservedCny()).isEqualByComparingTo(reservation);
        assertThat(runtime.snapshot(task, "default").budget().unknownCny()).isEqualByComparingTo(reservation);
        assertThat(runtime.snapshot(task, "default").budget().paidCallsBlocked()).isTrue();
        var next = persistence.generate(task, "default", "second-prepare", new Generate(revision(task), brief.briefId(), cny("1")));
        try (var worker = worker()) {
            worker.process(next);
        }
        assertThat(runs.run(task, "default", next.runId()).orElseThrow().status()).isEqualTo("FAILED");
        assertThat(runtime.findCharge(task, "default", next.runId(), "storyboard:" + next.runId())).isEmpty();
        assertThat(runs.workspace(task, "default").orElseThrow().activeRunId()).isNull();
        assertThat(count("creator_creation_storyboard", task)).isEqualTo(1);
        verify(generator, times(1)).generate(anyString(), anyString(), eq(4096));
    }

    @Test
    void workerTakeoverOfPreviouslySentCallNeverPhysicallyCallsModelAgain() {
        String task = task("default");
        var brief = persistence.saveBrief(task, "default", "brief", brief(0, List.of()));
        initialize(task);
        var job = persistence.generate(task, "default", "prepare", new Generate(revision(task), brief.briefId(), cny("2")));
        var previous = claim(job, "lost-worker");
        String key = "storyboard:" + job.runId();
        var queuedPrice = codec.read(previous.requestJson()).path("price");
        runtime.reserve(task, "default", job.runId(), previous.leaseOwner(), previous.leaseGeneration(), key,
                "STORYBOARD_TEXT", queuedPrice.path("reservationCny").decimalValue(), codec.write(queuedPrice));
        runtime.markSent(task, "default", key, previous.leaseOwner(), previous.leaseGeneration());
        jdbc.update("UPDATE creator_creation_run SET lease_expires_at=TIMESTAMPADD(SECOND,-1,NOW(6)) WHERE run_id=?", job.runId());
        assertThat(mapper.candidates()).extracting(Job::runId).contains(job.runId());
        try (var worker = worker()) {
            worker.process(job);
        }
        var recovered = runs.run(task, "default", job.runId()).orElseThrow();
        assertThat(recovered.leaseGeneration()).isEqualTo(previous.leaseGeneration() + 1);
        assertThat(recovered.status()).isEqualTo("FAILED");
        assertThat(runs.workspace(task, "default").orElseThrow().activeRunId()).isNull();
        assertThat(runtime.findCharge(task, "default", job.runId(), key).orElseThrow().status()).isEqualTo("UNKNOWN");
        assertThat(count("creator_creation_storyboard", task)).isZero();
        verify(generator, never()).generate(anyString(), anyString(), anyInt());
    }

    @Test
    void inFlightCancellationDiscardsLateCandidateButStillSettlesLateUsage() throws Exception {
        String task = task("default");
        var brief = persistence.saveBrief(task, "default", "brief", brief(0, List.of()));
        initialize(task);
        var job = persistence.generate(task, "default", "prepare", new Generate(revision(task), brief.briefId(), cny("2")));
        String chargeKey = "storyboard:" + job.runId();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var ignoredInterrupt = new CountDownLatch(1);
        var lateSettled = new CountDownLatch(1);
        when(generator.generate(anyString(), anyString(), eq(4096))).thenAnswer(invocation -> {
            entered.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (release.getCount() != 0) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new IllegalStateException("受控供应商等待超时");
                try {
                    if (!release.await(remaining, TimeUnit.NANOSECONDS)) throw new IllegalStateException("受控供应商等待超时");
                } catch (InterruptedException ignored) {
                    // 模拟已发出的供应商请求忽略取消，保持等待直到测试明确释放其晚结果。
                    ignoredInterrupt.countDown();
                }
            }
            return new StoryboardTextGenerator.Raw(codec.write(candidate(null, List.of("brief:user"), List.of())),
                    1000, 500, "configured-test-text-model");
        });
        // 此观测层仅在真实事务结算提交后通知测试；其它方法原样委托真实运行服务及数据库。
        var observedRuntime = mock(CreationRuntimeService.class, delegatesTo(runtime));
        doAnswer(invocation -> {
            var settled = runtime.settle(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2),
                    invocation.getArgument(3), invocation.getArgument(4), invocation.getArgument(5));
            lateSettled.countDown();
            return settled;
        }).when(observedRuntime).settle(eq(task), eq("default"), eq(chargeKey), any(BigDecimal.class), anyString(), anyString());
        var worker = new StoryboardWorker(mapper, observedRuntime, persistence, generator, codec, JSON);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var processing = executor.submit(() -> worker.process(job));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(runtime.cancel(task, "default", job.runId()).status()).isEqualTo("CANCEL_REQUESTED");
            processing.get(8, TimeUnit.SECONDS);
            assertThat(ignoredInterrupt.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(runs.run(task, "default", job.runId()).orElseThrow().status()).isEqualTo("CANCELLED");
            assertThat(runs.workspace(task, "default").orElseThrow().activeRunId()).isNull();
            assertThat(runtime.findCharge(task, "default", job.runId(), chargeKey).orElseThrow().status()).isEqualTo("UNKNOWN");
            assertThat(mapper.job(task, "default", job.runId()).orElseThrow().storyboardId()).isNull();
            assertThat(count("creator_creation_storyboard", task)).isZero();
            release.countDown();
            assertThat(lateSettled.await(5, TimeUnit.SECONDS)).isTrue();
            var settled = runtime.findCharge(task, "default", job.runId(), chargeKey).orElseThrow();
            assertThat(settled.status()).isEqualTo("SETTLED");
            assertThat(settled.knownCostCny()).isEqualByComparingTo("0.004320");
            assertThat(runtime.snapshot(task, "default").budget().knownCny()).isEqualByComparingTo("0.004320");
            assertThat(runtime.snapshot(task, "default").budget().unknownCny()).isZero();
            assertThat(runs.run(task, "default", job.runId()).orElseThrow().status()).isEqualTo("CANCELLED");
            assertThat(mapper.job(task, "default", job.runId()).orElseThrow().storyboardId()).isNull();
            assertThat(count("creator_creation_storyboard", task)).isZero();
            verify(generator, times(1)).generate(anyString(), anyString(), eq(4096));
        } finally {
            release.countDown();
            worker.close();
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void timelineApplyCreatesNewBriefAndBoardAndKeepsBothParents() {
        String task = task("default");
        String voice = audioAsset(task, "NARRATION", 3000, "c");
        var brief = persistence.saveBrief(task, "default", "timeline-brief", new BriefInput(revision(task),
                "时间线演示", "用户材料", "新手", 2000, "USER_NARRATION", "USER_PROVIDED", List.of(voice), "", null));
        var scene = new Scene("scene-1", "TITLE_CARD", "开场", List.of("用户提供的内容"), "", 2000,
                null, null, null, "CONTAIN", "NARRATION", voice,
                List.of(new Cue(0, 3000, "真实录音字幕")), List.of("brief:user"));
        var board = persistence.save(task, "default", "timeline-board",
                new Save(revision(task), brief.briefId(), null, new Candidate(List.of(scene))));
        var audio = new StoryboardAudioService(mapper, persistence, codec, new StoryboardCompiler(JSON), media, JSON);

        var report = audio.timing(task, "default", board.storyboardId());
        assertThat(report.scenes().getFirst().proposals()).extracting(TimingProposal::code).contains("EXTEND_SCENE");
        assertThat(report.scenes().getFirst().proposals().getFirst().proposedDurationMs()).isEqualTo(3000);
        long before = revision(task);
        var result = audio.applyTimeline(task, "default", "timeline-key", board.storyboardId(),
                new TimelineApply(before, List.of(new TimelineAdjustment("scene-1", "EXTEND_SCENE", 3000))));

        // 新简报记录新的用户意图，旧简报与旧故事板的原文必须原样保留。
        assertThat(result.briefRevision()).isEqualTo(2);
        assertThat(result.targetDurationMs()).isEqualTo(3000);
        assertThat(result.applied()).containsExactly(new AppliedAdjustment("scene-1", "EXTEND_SCENE", 2000, 3000));
        assertThat(result.board().check().renderable()).isTrue();
        assertThat(mapper.brief(task, "default", brief.briefId()).orElseThrow().documentJson()).isEqualTo(brief.documentJson());
        assertThat(mapper.board(task, "default", board.storyboardId()).orElseThrow().documentJson()).isEqualTo(board.documentJson());
        var saved = mapper.board(task, "default", result.board().storyboardId()).orElseThrow();
        assertThat(saved.parentStoryboardId()).isEqualTo(board.storyboardId());
        assertThat(saved.briefId()).isNotEqualTo(brief.briefId());
        assertThat(codec.candidate(codec.read(saved.documentJson()).path("candidate")).scenes().getFirst().durationMs())
                .isEqualTo(3000);
        assertThat(revision(task)).isEqualTo(before + 1);

        // 同一幂等键重放返回同一版本，不重复写简报或故事板。
        var replay = audio.applyTimeline(task, "default", "timeline-key", board.storyboardId(),
                new TimelineApply(before, List.of(new TimelineAdjustment("scene-1", "EXTEND_SCENE", 3000))));
        assertThat(replay.board().storyboardId()).isEqualTo(result.board().storyboardId());
        assertThat(count("creator_creation_brief", task)).isEqualTo(2);
        assertThat(count("creator_creation_storyboard", task)).isEqualTo(2);
    }

    @Test
    void timelineApplyMovesMusicSelectionWithTheNewTotalDuration() {
        String task = task("default");
        String first = audioAsset(task, "NARRATION", 3000, "c");
        String second = audioAsset(task, "NARRATION", 3000, "d");
        String music = audioAsset(task, "MUSIC", 8000, "e");
        var bgm = new BackgroundMusic(music, 1000, 6000, cny("-18"), 500, 500, true);
        var brief = persistence.saveBrief(task, "default", "music-timeline", new BriefInput(revision(task),
                "音乐时间线", "用户材料", "新手", 5000, "USER_NARRATION", "USER_PROVIDED",
                List.of(first, second, music), "", bgm));
        var scenes = List.of(
                new Scene("scene-1", "TITLE_CARD", "开场", List.of("第一段"), "", 2000, null, null, null,
                        "CONTAIN", "NARRATION", first, List.of(new Cue(0, 3000, "第一段字幕")), List.of("brief:user")),
                new Scene("scene-2", "TITLE_CARD", "结尾", List.of("第二段"), "", 3000, null, null, null,
                        "CONTAIN", "NARRATION", second, List.of(new Cue(0, 3000, "第二段字幕")), List.of("brief:user")));
        var board = persistence.save(task, "default", "music-timeline-board",
                new Save(revision(task), brief.briefId(), null, new Candidate(scenes)));
        var audio = new StoryboardAudioService(mapper, persistence, codec, new StoryboardCompiler(JSON), media, JSON);

        var result = audio.applyTimeline(task, "default", "music-timeline-key", board.storyboardId(),
                new TimelineApply(revision(task), List.of(new TimelineAdjustment("scene-1", "EXTEND_SCENE", 3000))));

        assertThat(result.targetDurationMs()).isEqualTo(6000);
        assertThat(result.notes()).anyMatch(text -> text.contains("7000ms"));
        var savedBrief = mapper.brief(task, "default", result.board().briefId()).orElseThrow();
        assertThat(codec.read(savedBrief.documentJson()).path("backgroundMusic").path("sourceOutMs").asLong())
                .isEqualTo(7000);
        assertThat(result.board().check().renderable()).isTrue();
        // 旧简报仍保留原来的音乐区间。
        assertThat(codec.read(brief.documentJson()).path("backgroundMusic").path("sourceOutMs").asLong()).isEqualTo(6000);
    }

    @Test
    void timelineApplyRefusesToStretchMusicBeyondItsRealDuration() {
        String task = task("default");
        String voice = audioAsset(task, "NARRATION", 7000, "c");
        String music = audioAsset(task, "MUSIC", 6000, "d");
        var bgm = new BackgroundMusic(music, 0, 5000, cny("-18"), 0, 0, false);
        var brief = persistence.saveBrief(task, "default", "short-music", new BriefInput(revision(task),
                "音乐不足", "用户材料", "新手", 5000, "USER_NARRATION", "USER_PROVIDED", List.of(voice, music), "", bgm));
        var scene = new Scene("scene-1", "TITLE_CARD", "开场", List.of("用户提供的内容"), "", 5000,
                null, null, null, "CONTAIN", "NARRATION", voice,
                List.of(new Cue(0, 7000, "真实录音字幕")), List.of("brief:user"));
        var board = persistence.save(task, "default", "short-music-board",
                new Save(revision(task), brief.briefId(), null, new Candidate(List.of(scene))));
        var audio = new StoryboardAudioService(mapper, persistence, codec, new StoryboardCompiler(JSON), media, JSON);
        long before = revision(task);

        assertThatThrownBy(() -> audio.applyTimeline(task, "default", "short-music-key", board.storyboardId(),
                new TimelineApply(before, List.of(new TimelineAdjustment("scene-1", "EXTEND_SCENE", 7000)))))
                .hasMessageContaining("背景音乐区间需要重新选择");
        assertThat(count("creator_creation_brief", task)).isEqualTo(1);
        assertThat(count("creator_creation_storyboard", task)).isEqualTo(1);
        assertThat(revision(task)).isEqualTo(before);
    }

    @Test
    void timelineApplyExtendsVideoSelectionTogetherWithTheScene() {
        String task = task("default");
        String video = videoAsset(task, 10000, "e");
        String voice = audioAsset(task, "NARRATION", 3000, "f");
        var brief = persistence.saveBrief(task, "default", "clip-timeline", new BriefInput(revision(task),
                "片段时间线", "用户材料", "新手", 2000, "USER_NARRATION", "USER_PROVIDED", List.of(video, voice), "", null));
        var scene = new Scene("scene-1", "VIDEO_CLIP", "片段", List.of(), "", 2000, video, 1000L, 3000L,
                "COVER", "NARRATION", voice, List.of(new Cue(0, 3000, "片段配音字幕")), List.of("brief:user"));
        var board = persistence.save(task, "default", "clip-timeline-board",
                new Save(revision(task), brief.briefId(), null, new Candidate(List.of(scene))));
        var audio = new StoryboardAudioService(mapper, persistence, codec, new StoryboardCompiler(JSON), media, JSON);

        var result = audio.applyTimeline(task, "default", "clip-timeline-key", board.storyboardId(),
                new TimelineApply(revision(task), List.of(new TimelineAdjustment("scene-1", "EXTEND_SCENE", 3000))));

        var saved = codec.candidate(codec.read(mapper.board(task, "default", result.board().storyboardId())
                .orElseThrow().documentJson()).path("candidate")).scenes().getFirst();
        assertThat(saved.durationMs()).isEqualTo(3000);
        assertThat(saved.sourceInMs()).isEqualTo(1000);
        assertThat(saved.sourceOutMs()).isEqualTo(4000);
        assertThat(result.board().check().renderable()).isTrue();
    }

    @Test
    void timelineApplyKeepsGeneratedAiVideoAssets() {
        String task = task("default");
        String prompt = "海边日落";
        String video = generatedVideoAsset(task, 10000, prompt, "e");
        String voice = audioAsset(task, "NARRATION", 3000, "f");
        var brief = persistence.saveBrief(task, "default", "ai-timeline", new BriefInput(revision(task),
                "AI 片段时间线", "用户材料", "新手", 2000, "USER_NARRATION", "USER_PROVIDED",
                List.of(video, voice), "", null));
        var scene = new Scene("scene-1", "AI_VIDEO", "日落", List.of(), "", 2000, video, 0L, 2000L,
                "COVER", "NARRATION", voice, List.of(new Cue(0, 3000, "配音字幕")), List.of("brief:user"), prompt);
        var board = persistence.save(task, "default", "ai-timeline-board",
                new Save(revision(task), brief.briefId(), null, new Candidate(List.of(scene))));
        var audio = new StoryboardAudioService(mapper, persistence, codec, new StoryboardCompiler(JSON), media, JSON);

        var result = audio.applyTimeline(task, "default", "ai-timeline-key", board.storyboardId(),
                new TimelineApply(revision(task), List.of(new TimelineAdjustment("scene-1", "EXTEND_SCENE", 3000))));

        var saved = codec.candidate(codec.read(mapper.board(task, "default", result.board().storyboardId())
                .orElseThrow().documentJson()).path("candidate")).scenes().getFirst();
        assertThat(saved.layout()).isEqualTo("AI_VIDEO");
        assertThat(saved.visualPrompt()).isEqualTo(prompt);
        assertThat(saved.sourceOutMs()).isEqualTo(3000);
        assertThat(result.board().check().renderable()).isTrue();
    }

    private static String generatedVideoAsset(String task, long durationMs, String prompt, String hashCharacter) {
        String id = videoAsset(task, durationMs, hashCharacter);
        String raw = jdbc.queryForObject("SELECT metadata_json FROM creator_creation_asset WHERE asset_id=?",
                String.class, id);
        try {
            ObjectNode metadata = (ObjectNode) JSON.readTree(raw);
            metadata.putObject("generated").put("kind", "AI_VIDEO").put("provider", "compatible")
                    .put("model", "test-video-1").put("promptSha256", StoryboardCodec.digest(prompt))
                    .put("prompt", prompt).put("durationMs", durationMs);
            jdbc.update("UPDATE creator_creation_asset SET metadata_json=? WHERE asset_id=?", codec.write(metadata), id);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
        return id;
    }

    @Test
    void timelineApplyRejectsExtendingBeyondAvailableVideo() {
        String task = task("default");
        String video = videoAsset(task, 3000, "e");
        String voice = audioAsset(task, "NARRATION", 3000, "f");
        var brief = persistence.saveBrief(task, "default", "short-clip", new BriefInput(revision(task),
                "片段不足", "用户材料", "新手", 2000, "USER_NARRATION", "USER_PROVIDED", List.of(video, voice), "", null));
        var scene = new Scene("scene-1", "VIDEO_CLIP", "片段", List.of(), "", 2000, video, 1000L, 3000L,
                "COVER", "NARRATION", voice, List.of(new Cue(0, 3000, "片段配音字幕")), List.of("brief:user"));
        var board = persistence.save(task, "default", "short-clip-board",
                new Save(revision(task), brief.briefId(), null, new Candidate(List.of(scene))));
        var audio = new StoryboardAudioService(mapper, persistence, codec, new StoryboardCompiler(JSON), media, JSON);

        // 画面只剩 0 ms 余量时只给补素材建议，不能凭时长提案延长。
        assertThat(audio.timing(task, "default", board.storyboardId()).proposals())
                .extracting(TimingProposal::code).contains("SUPPLY_MORE_VIDEO");
        assertThatThrownBy(() -> audio.applyTimeline(task, "default", "short-clip-key", board.storyboardId(),
                new TimelineApply(revision(task), List.of(new TimelineAdjustment("scene-1", "EXTEND_SCENE", 3000)))))
                .hasMessageContaining("当前不存在 EXTEND_SCENE 提案");
        assertThat(count("creator_creation_storyboard", task)).isEqualTo(1);
    }

    @Test
    void timelineApplyRefusesAStoryboardWhoseBriefIsNoLongerCurrent() {
        String task = task("default");
        String voice = audioAsset(task, "NARRATION", 3000, "c");
        var brief = persistence.saveBrief(task, "default", "old-brief", new BriefInput(revision(task),
                "旧简报", "用户材料", "新手", 2000, "USER_NARRATION", "USER_PROVIDED", List.of(voice), "", null));
        var scene = new Scene("scene-1", "TITLE_CARD", "开场", List.of("用户提供的内容"), "", 2000,
                null, null, null, "CONTAIN", "NARRATION", voice,
                List.of(new Cue(0, 3000, "真实录音字幕")), List.of("brief:user"));
        var board = persistence.save(task, "default", "old-brief-board",
                new Save(revision(task), brief.briefId(), null, new Candidate(List.of(scene))));
        persistence.saveBrief(task, "default", "new-brief", brief(revision(task), List.of()));
        var audio = new StoryboardAudioService(mapper, persistence, codec, new StoryboardCompiler(JSON), media, JSON);

        assertThatThrownBy(() -> audio.applyTimeline(task, "default", "old-brief-key", board.storyboardId(),
                new TimelineApply(revision(task), List.of(new TimelineAdjustment("scene-1", "EXTEND_SCENE", 3000)))))
                .hasMessageContaining("当前简报已更新");
        assertThat(count("creator_creation_brief", task)).isEqualTo(2);
        assertThat(count("creator_creation_storyboard", task)).isEqualTo(1);
    }

    @Test
    void timelineApplyStaysRenderableWhenTheNextSceneStartsOnAFractionalMillisecond() {
        String task = task("default");
        String first = audioAsset(task, "NARRATION", 3060, "c");
        String second = audioAsset(task, "NARRATION", 1000, "d");
        var brief = persistence.saveBrief(task, "default", "fraction-brief", new BriefInput(revision(task),
                "帧边界时间线", "用户材料", "新手", 3000, "USER_NARRATION", "USER_PROVIDED",
                List.of(first, second), "", null));
        var scenes = List.of(
                new Scene("scene-1", "TITLE_CARD", "开场", List.of("第一段"), "", 2000, null, null, null,
                        "CONTAIN", "NARRATION", first, List.of(new Cue(0, 3060, "第一段字幕")), List.of("brief:user")),
                new Scene("scene-2", "TITLE_CARD", "结尾", List.of("第二段"), "", 1000, null, null, null,
                        "CONTAIN", "NARRATION", second, List.of(new Cue(0, 1000, "第二段字幕")), List.of("brief:user")));
        var board = persistence.save(task, "default", "fraction-board",
                new Save(revision(task), brief.briefId(), null, new Candidate(scenes)));
        var audio = new StoryboardAudioService(mapper, persistence, codec, new StoryboardCompiler(JSON), media, JSON);

        var report = audio.timing(task, "default", board.storyboardId());
        assertThat(report.scenes().getFirst().proposals().getFirst().proposedDurationMs()).isEqualTo(3067);
        // 92 帧的场景起点是 3066.67ms；第二段字幕结束在 4066.67ms 的片尾，不能被四舍五入成 4067 后误判越界。
        var result = audio.applyTimeline(task, "default", "fraction-key", board.storyboardId(),
                new TimelineApply(revision(task), List.of(new TimelineAdjustment("scene-1", "EXTEND_SCENE", 3067))));

        assertThat(result.targetDurationMs()).isEqualTo(4067);
        assertThat(result.board().check().renderable()).isTrue();
        assertThat(result.board().check().problems()).isEmpty();
    }

    @Test
    void timelineApplyStillWorksWhenAnotherSceneIsNotPreparedYet() {
        String task = task("default");
        String first = audioAsset(task, "NARRATION", 3060, "c");
        var brief = persistence.saveBrief(task, "default", "guard-brief", new BriefInput(revision(task),
                "边界守卫", "用户材料", "新手", 3000, "USER_NARRATION", "USER_PROVIDED", List.of(first), "", null));
        var scenes = List.of(
                new Scene("scene-1", "TITLE_CARD", "开场", List.of("第一段"), "", 2000, null, null, null,
                        "CONTAIN", "NARRATION", first, List.of(new Cue(0, 3060, "第一段字幕")), List.of("brief:user")),
                // 第二段还没有真实录音；这是既有待准备状态，不是本次采纳引入的问题。
                new Scene("scene-2", "TITLE_CARD", "结尾", List.of("第二段"), "第二段文稿", 1000, null, null, null,
                        "CONTAIN", "NARRATION", null, List.of(), List.of("brief:user")));
        var board = persistence.save(task, "default", "guard-board",
                new Save(revision(task), brief.briefId(), null, new Candidate(scenes)));
        var audio = new StoryboardAudioService(mapper, persistence, codec, new StoryboardCompiler(JSON), media, JSON);

        var result = audio.applyTimeline(task, "default", "guard-key", board.storyboardId(),
                new TimelineApply(revision(task), List.of(new TimelineAdjustment("scene-1", "EXTEND_SCENE", 3067))));

        assertThat(result.applied()).hasSize(1);
        assertThat(result.board().check().renderable()).isFalse();
        assertThat(result.board().check().problems()).allMatch(problem -> "scene-2".equals(problem.sceneId()));
    }

    @Test
    void ambienceIsStoredAsAnIndependentBedAndCannotReuseMusicSource() {
        String task = task("default");
        String music = audioAsset(task, "MUSIC", 8000, "b");
        String bed = audioAsset(task, "MUSIC", 12000, "c");
        var bgm = new BackgroundMusic(music, 1000, 6000, cny("-18"), 500, 500, true);
        var ambience = new Ambience(bed, 0, 5000, cny("-24"), 200, 300);
        var brief = persistence.saveBrief(task, "default", "ambience-brief", new BriefInput(revision(task),
                "环境声演示", "用户材料", "新手", 5000, "NO_DIALOGUE", "USER_PROVIDED",
                List.of(music, bed), "", bgm, ambience));
        var document = codec.read(brief.documentJson());
        assertThat(document.path("ambience").path("assetId").asText()).isEqualTo(bed);
        assertThat(document.path("ambience").path("gainDb").asDouble()).isEqualTo(-24.0);
        // 环境声没有压低开关，与背景音乐是两路独立音轨。
        assertThat(document.path("ambience").has("ducking")).isFalse();
        assertThat(document.path("backgroundMusic").path("assetId").asText()).isEqualTo(music);

        long after = revision(task);
        conflict(() -> persistence.saveBrief(task, "default", "ambience-same", new BriefInput(after,
                "环境声演示", "用户材料", "新手", 5000, "NO_DIALOGUE", "USER_PROVIDED", List.of(music), "",
                bgm, new Ambience(music, 0, 5000, cny("-24"), 0, 0))));
        assertThat(count("creator_creation_brief", task)).isEqualTo(1);
    }

    @Test
    void timelineApplyMovesAmbienceWithTheNewTotalDuration() {
        String task = task("default");
        String first = audioAsset(task, "NARRATION", 3060, "c");
        String second = audioAsset(task, "NARRATION", 1000, "d");
        String bed = audioAsset(task, "MUSIC", 12000, "e");
        var ambience = new Ambience(bed, 500, 3500, cny("-24"), 200, 300);
        var brief = persistence.saveBrief(task, "default", "ambience-timeline", new BriefInput(revision(task),
                "环境声时间线", "用户材料", "新手", 3000, "USER_NARRATION", "USER_PROVIDED",
                List.of(first, second, bed), "", null, ambience));
        var scenes = List.of(
                new Scene("scene-1", "TITLE_CARD", "开场", List.of("第一段"), "", 2000, null, null, null,
                        "CONTAIN", "NARRATION", first, List.of(new Cue(0, 3060, "第一段字幕")), List.of("brief:user")),
                new Scene("scene-2", "TITLE_CARD", "结尾", List.of("第二段"), "", 1000, null, null, null,
                        "CONTAIN", "NARRATION", second, List.of(new Cue(0, 1000, "第二段字幕")), List.of("brief:user")));
        var board = persistence.save(task, "default", "ambience-timeline-board",
                new Save(revision(task), brief.briefId(), null, new Candidate(scenes)));
        var audio = new StoryboardAudioService(mapper, persistence, codec, new StoryboardCompiler(JSON), media, JSON);

        var result = audio.applyTimeline(task, "default", "ambience-timeline-key", board.storyboardId(),
                new TimelineApply(revision(task), List.of(new TimelineAdjustment("scene-1", "EXTEND_SCENE", 3067))));

        assertThat(result.targetDurationMs()).isEqualTo(4067);
        assertThat(result.notes()).anyMatch(text -> text.contains("环境声") && text.contains("4567ms"));
        var savedBrief = mapper.brief(task, "default", result.board().briefId()).orElseThrow();
        assertThat(codec.read(savedBrief.documentJson()).path("ambience").path("sourceOutMs").asLong())
                .isEqualTo(4567);
        assertThat(result.board().check().renderable()).isTrue();
    }

    @Test
    void sampleConfirmationReplaysWithoutExtraBudgetOrRunAndBlocksTaskDeletion() {
        String task=task("default");initialize(task);
        var brief=persistence.saveBrief(task,"default","execution-brief",brief(revision(task),List.of()));
        var board=persistence.save(task,"default","execution-board",new Save(revision(task),brief.briefId(),null,candidate(null,List.of("brief:user"),List.of())));
        var service=executions();
        var input=executionInput(task,board,"SAMPLE",BigDecimal.ZERO);
        var started=service.start(task,"default","sample-confirm",input);
        assertThat(service.start(task,"default","sample-confirm",input).executionId()).isEqualTo(started.executionId());
        assertThat(count("creator_creation_execution",task)).isEqualTo(1);
        assertThat(tasks.countDeletionBlockers(task,"default")).isEqualTo(1);
        assertThat(count("creator_creation_run",task)).isZero();
        assertThatThrownBy(()->service.start(task,"default","sample-confirm",new ExecutionModels.Start(input.expectedWorkspaceRevision(),board.storyboardId(),board.documentHash(),"FULL",input.providerFingerprint(),BigDecimal.ZERO))).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void sampleDispatchIsDurableAndNeverAllocatesDraftVersionOrFullApproval() {
        String task=task("default");initialize(task);
        var brief=persistence.saveBrief(task,"default","execution-brief",brief(revision(task),List.of()));
        var board=persistence.save(task,"default","execution-board",new Save(revision(task),brief.briefId(),null,candidate(null,List.of("brief:user"),List.of())));
        var service=executions();
        var started=service.start(task,"default","sample-confirm",executionInput(task,board,"SAMPLE",BigDecimal.ZERO));
        var work=service.advance(task,"default",started.executionId());
        assertThat(work.action()).isEqualTo("SAMPLE");
        assertThat(executions().advance(task,"default",started.executionId()).execution().currentRunId()).isEqualTo(work.execution().currentRunId());
        assertThat(count("creator_creation_run",task)).isEqualTo(1);
        assertThat(count("creator_draft_video",task)).isZero();
        assertThat(jdbc.queryForObject("SELECT approval_kind FROM creator_creation_approval WHERE task_id=?",String.class,task)).isEqualTo("PREPARATION");
        assertThat(service.cancel(task,"default",started.executionId()).status()).isEqualTo("CANCELLED");
        assertThat(runtime.snapshot(task,"default").activeRun()).isNull();
        assertThat(tasks.countDeletionBlockers(task,"default")).isZero();
    }

    @Test
    void externalRevisionBetweenConfirmationAndDispatchStopsWithoutStartingAnyRun() {
        String task=task("default");initialize(task);
        var brief=persistence.saveBrief(task,"default","execution-brief",brief(revision(task),List.of()));
        var board=persistence.save(task,"default","execution-board",new Save(revision(task),brief.briefId(),null,candidate(null,List.of("brief:user"),List.of())));
        var service=executions();
        var started=service.start(task,"default","sample-confirm",executionInput(task,board,"SAMPLE",BigDecimal.ZERO));
        runtime.updateBudget(task,"default",revision(task),cny("21"));
        assertThat(service.advance(task,"default",started.executionId())).isNull();
        var result=service.get(task,"default",started.executionId());
        assertThat(result.status()).isEqualTo("WAITING_USER");assertThat(result.errorCode()).isEqualTo("WORKSPACE_CHANGED");
        assertThat(count("creator_creation_run",task)).isZero();
    }

    @Test
    void fullWithoutRealPublishingConfirmationIsRejectedAndCreatesNothing() {
        String task=task("default");initialize(task);
        var brief=persistence.saveBrief(task,"default","execution-brief",brief(revision(task),List.of()));
        var board=persistence.save(task,"default","execution-board",new Save(revision(task),brief.briefId(),null,candidate(null,List.of("brief:user"),List.of())));
        var service=executions();
        conflict(()->service.start(task,"default","full-confirm",executionInput(task,board,"FULL",cny("5"))));
        assertThat(count("creator_creation_execution",task)).isZero();assertThat(count("creator_creation_run",task)).isZero();
    }

    @Test
    void subtitleRevisionHasReadOnlyPreviewAndAtomicReplayableAdoption() {
        String task=task("default");initialize(task);
        var brief=persistence.saveBrief(task,"default","rev-brief",brief(revision(task),List.of()));
        var board=persistence.save(task,"default","rev-board",new Save(revision(task),brief.briefId(),null,candidate(null,List.of("brief:user"),List.of())));
        var service=revisions();long before=revision(task);
        var preview=service.propose(task,"default","subtitle-proposal",new RevisionModels.Propose(before,board.storyboardId(),"字幕大一点",null));
        assertThat(preview.plan().voiceSceneIds()).isEmpty();assertThat(preview.plan().estimatedPreparationCny()).isZero();
        assertThat(revision(task)).isEqualTo(before);assertThat(count("creator_creation_storyboard",task)).isEqualTo(1);
        var applied=service.apply(task,"default",preview.proposalId(),new RevisionModels.Apply(before));
        assertThat(service.apply(task,"default",preview.proposalId(),new RevisionModels.Apply(before)).storyboardId()).isEqualTo(applied.storyboardId());
        assertThat(applied.parentStoryboardId()).isEqualTo(board.storyboardId());
        var updatedBrief=mapper.brief(task,"default",applied.briefId()).orElseThrow();
        assertThat(codec.read(updatedBrief.documentJson()).path("subtitleFontSize").asInt()).isEqualTo(42);
        assertThat(codec.read(updatedBrief.sourceJson()).path("revisions")).hasSize(1);
        assertThat(codec.read(mapper.brief(task,"default",brief.briefId()).orElseThrow().documentJson()).has("subtitleFontSize")).isFalse();
        assertThat(count("creator_creation_run",task)).isZero();assertThat(count("creator_creation_cost_entry",task)).isZero();
    }

    @Test
    void revisionApplyRejectsAStaleWorkspaceWithoutOverwritingEitherVersion() {
        String task=task("default");initialize(task);
        var brief=persistence.saveBrief(task,"default","rev-brief",brief(revision(task),List.of()));
        var board=persistence.save(task,"default","rev-board",new Save(revision(task),brief.briefId(),null,candidate(null,List.of("brief:user"),List.of())));
        var service=revisions();long before=revision(task);
        var preview=service.propose(task,"default","stale-proposal",new RevisionModels.Propose(before,board.storyboardId(),"字幕大一点",null));
        runtime.updateBudget(task,"default",before,cny("21"));
        conflict(()->service.apply(task,"default",preview.proposalId(),new RevisionModels.Apply(before)));
        assertThat(count("creator_creation_brief",task)).isEqualTo(1);assertThat(count("creator_creation_storyboard",task)).isEqualTo(1);
    }

    @Test
    void revisionRejectsUnknownNestedFieldsAndNeverAcceptsForeignAssets() {
        String task=task("default");initialize(task);
        var brief=persistence.saveBrief(task,"default","rev-brief",brief(revision(task),List.of()));
        var board=persistence.save(task,"default","rev-board",new Save(revision(task),brief.briefId(),null,candidate(null,List.of("brief:user"),List.of())));
        var service=revisions();
        var injected=JSON.createObjectNode().put("mode","NO_DIALOGUE").put("command","invalid-command");
        conflict(()->service.propose(task,"default","invalid-fields",new RevisionModels.Propose(revision(task),board.storyboardId(),null,List.of(new RevisionModels.Change("SET_AUDIO","scene-1",injected)))));
        String foreign=asset(task("other"),"other","foreign.png","c");
        var foreignRequest=new RevisionModels.Propose(revision(task),board.storyboardId(),null,
                List.of(new RevisionModels.Change("SET_VISUAL","scene-1",JSON.getNodeFactory().textNode(foreign))));
        rejected(()->service.propose(task,"default","foreign-asset",foreignRequest),404);
        assertThat(count("creator_creation_revision_proposal",task)).isZero();
    }

    private RevisionService revisions() {
        var compiler=new StoryboardCompiler(JSON);
        return tx(new RevisionService(session.getMapper(RevisionMapper.class),mapper,persistence,
                new com.link.linkagent.creator.creation.storyboard.service.StoryboardService(mapper,persistence,compiler,codec,media,runtime,assets),
                codec,compiler,assets,media,runtime,session.getMapper(ExecutionMapper.class),new RevisionInterpreter(JSON),JSON,
                new com.link.linkagent.creator.creation.voice.service.CreationVoiceProperties(),new com.link.linkagent.creator.creation.aivideo.service.CreationVideoProperties(),
                new CreatorMediaProperties(),BillingExchangeRate.fixed(cny("7.2"))));
    }

    private ExecutionService executions() {
        var compiler=new StoryboardCompiler(JSON);
        var voice=mock(CreationVoiceService.class);var video=mock(CreationVideoService.class);
        var renderer=new FixedProjectRendererProperties();renderer.setEnabled(true);
        var worker=new CreationWorkerProperties();worker.setEnabled(true);
        var planner=new ExecutionPlanner(mapper,persistence,codec,compiler,voice,video,runtime,prices,
                new CreatorMediaProperties(),CreationCapabilityGate.defaults(true,true),BillingExchangeRate.fixed(cny("7.2")),renderer,worker);
        return tx(new ExecutionService(session.getMapper(ExecutionMapper.class),planner,media,runs,runtime,mapper,codec,voice,video,
                mock(AutomaticProductionPlanService.class),session.getMapper(CreationMediaMapper.class),session.getMapper(CreationPackageMapper.class)));
    }
    private ExecutionModels.Start executionInput(String task,Board board,String kind,BigDecimal limit){
        var quote=executions().estimate(task,"default",new ExecutionModels.EstimateInput(revision(task),board.storyboardId(),kind));
        return new ExecutionModels.Start(quote.workspaceRevision(),board.storyboardId(),board.documentHash(),kind,quote.providerFingerprint(),limit);
    }
    @Test void fullEstimateExplainsMissingReviewCredentialsWhileAReadySampleNeedsNoCloudReview(){
        String task=task("default");initialize(task);
        var brief=persistence.saveBrief(task,"default","cloud-brief",brief(revision(task),List.of()));
        var board=persistence.save(task,"default","cloud-board",new Save(revision(task),brief.briefId(),null,candidate(null,List.of("brief:user"),List.of())));
        var service=executions();
        assertThat(service.estimate(task,"default",new ExecutionModels.EstimateInput(revision(task),board.storyboardId(),"SAMPLE")).blockers()).isEmpty();
        assertThat(service.estimate(task,"default",new ExecutionModels.EstimateInput(revision(task),board.storyboardId(),"FULL")).blockers())
            .contains("完整试映尚未配置密钥或视频理解模型，请先完成制作配置后再制作整片");
        assertThat(count("creator_creation_run",task)).isZero();
    }
    @Test void changedConfigurationAndRepeatedLocalFailuresStopBeforeNewCalls(){
        String task=task("default");initialize(task);
        var brief=persistence.saveBrief(task,"default","guard-brief",brief(revision(task),List.of()));
        var board=persistence.save(task,"default","guard-board",new Save(revision(task),brief.briefId(),null,candidate(null,List.of("brief:user"),List.of())));
        var input=executionInput(task,board,"SAMPLE",BigDecimal.ZERO);var service=executions();
        conflict(()->service.start(task,"default","wrong-config",new ExecutionModels.Start(input.expectedWorkspaceRevision(),input.storyboardId(),input.storyboardHash(),input.kind(),"0".repeat(64),input.maxAdditionalCostCny())));
        var started=service.start(task,"default","guarded",input);service.dispatchFailed(task,"default",started.executionId());service.dispatchFailed(task,"default",started.executionId());
        assertThat(service.get(task,"default",started.executionId()).status()).isEqualTo("ACTIVE");service.dispatchFailed(task,"default",started.executionId());
        assertThat(service.get(task,"default",started.executionId()).errorCode()).isEqualTo("DISPATCH_FAILED");assertThat(service.advance(task,"default",started.executionId())).isNull();
        assertThat(count("creator_creation_run",task)).isZero();service.cancel(task,"default",started.executionId());
    }

    private StoryboardWorker worker() {
        return new StoryboardWorker(mapper, runtime, persistence, generator, codec, JSON);
    }

    private StoryboardPersistence service(StoryboardMapper selected) {
        return tx(new StoryboardPersistence(selected, media, tasks, runs, workspace, runtime, assets,
                codec, JSON, prices, generator));
    }

    @SuppressWarnings("unchecked")
    private static <T> T tx(T target) {
        var factory = new ProxyFactory(target);
        factory.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        return (T) factory.getProxy();
    }

    private static String task(String owner) {
        String task = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO creator_task(task_id,user_id,task_name,video_type,status) VALUES (?,?,'隔离故事板测试','知识分享','DRAFT')", task, owner);
        media.adoptMediaWorkspace(task, owner);
        return task;
    }

    private static String material(String task, String content) {
        jdbc.update("INSERT INTO creator_material(task_id,material_type,content) VALUES (?,'MANUSCRIPT',?)", task, content);
        return "material:" + jdbc.queryForObject("SELECT id FROM creator_material WHERE task_id=? AND material_type='MANUSCRIPT'", Long.class, task);
    }

    private static String asset(String task, String owner, String fileName, String hashCharacter) {
        String id = UUID.randomUUID().toString();
        String key = "users/" + owner + "/tasks/" + task + "/assets/" + id;
        String objects = codec.write(List.of(new StoredFile("NORMALIZED", key + "/normalized.png", "image/png", 123, hashCharacter.repeat(64))));
        // 只插入数据库准备事实，不把固定字节和 metadata 当作本轮真实上传、哈希或解码的证据。
        jdbc.update("""
                INSERT INTO creator_creation_asset(asset_id,task_id,owner_id,idempotency_key,kind,file_name,
                  content_type,expected_bytes,expected_sha256,bucket_name,source_key,status,source_sha256,metadata_json,objects_json)
                VALUES (?,?,?,?,'IMAGE',?,'image/png',123,?,'isolated-storyboard',?,'READY',?,
                  '{"width":640,"height":360,"format":"png"}',?)
                """, id, task, owner, "asset-" + id, fileName, hashCharacter.repeat(64), key + "/original.png", hashCharacter.repeat(64), objects);
        return id;
    }

    private static String audioAsset(String task,String kind,long durationMs,String hashCharacter) {
        String id=asset(task,"default","fixture.wav",hashCharacter);String key="fixtures/"+id+"/normalized.wav";
        var metadata=JSON.createObjectNode();var normalized=metadata.putObject("normalized");
        normalized.putObject("format").put("duration",durationMs/1000.0);
        normalized.putArray("streams").addObject().put("codec_type","audio").put("codec_name","pcm_s16le")
                .put("sample_rate",48000).put("channels",2).put("duration",durationMs/1000.0).put("start_time",0);
        String objects=codec.write(List.of(new StoredFile("NORMALIZED",key,"audio/wav",durationMs*192+44,hashCharacter.repeat(64))));
        jdbc.update("UPDATE creator_creation_asset SET kind=?,content_type='audio/wav',metadata_json=?,objects_json=? WHERE asset_id=?",kind,codec.write(metadata),objects,id);
        return id;
    }

    private static String videoAsset(String task,long durationMs,String hashCharacter) {
        String id=asset(task,"default","fixture.mp4",hashCharacter);String key="fixtures/"+id+"/normalized.mp4";
        var metadata=JSON.createObjectNode();var normalized=metadata.putObject("normalized");
        normalized.putObject("format").put("duration",durationMs/1000.0);
        normalized.putArray("streams").addObject().put("codec_type","video").put("codec_name","h264")
                .put("duration",durationMs/1000.0).put("width",1280).put("height",720).put("start_time",0);
        String objects=codec.write(List.of(new StoredFile("NORMALIZED",key,"video/mp4",durationMs*512,hashCharacter.repeat(64))));
        jdbc.update("UPDATE creator_creation_asset SET kind='VIDEO_CLIP',content_type='video/mp4',metadata_json=?,objects_json=? WHERE asset_id=?",
                codec.write(metadata),objects,id);
        return id;
    }

    private static BriefInput brief(long expected, List<String> ids) {
        return new BriefInput(expected, "真实素材与分镜测试", "讲清楚用户提供的事实", "第一次制作视频的 UP 主", 5000,
                "NO_DIALOGUE", "USER_PROVIDED", ids, "");
    }

    private static Candidate candidate(String asset, List<String> sourceRefs, List<Cue> cues) {
        return new Candidate(List.of(new Scene("scene-1", asset == null ? "TITLE_CARD" : "IMAGE_CARD", "开场", asset == null ? List.of("用户提供的内容") : List.of(),
                "", 5000, asset, null, null, "CONTAIN", "NO_DIALOGUE", null, cues, sourceRefs)));
    }

    private static void initialize(String task) {
        runtime.initialize(task, "default", "budget", revision(task), cny("20"));
    }

    private static Run claim(Job job, String worker) {
        return runtime.claim(job.taskId(), job.ownerId(), job.runId(), worker, 30).orElseThrow();
    }

    private static long revision(String task) {
        return runs.workspace(task, "default").orElseThrow().workspaceRevision();
    }

    private static BigDecimal cny(String value) { return new BigDecimal(value); }

    private static int count(String table, String task) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE task_id=?", Integer.class, task);
    }

    private static void conflict(Runnable action) { rejected(action, 409); }

    private static void rejected(Runnable action, int status) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ResponseStatusException.class,
                ex -> assertThat(ex.getStatusCode().value()).isEqualTo(status));
    }
}
