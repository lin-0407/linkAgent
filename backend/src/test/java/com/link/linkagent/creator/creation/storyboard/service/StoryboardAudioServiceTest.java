package com.link.linkagent.creator.creation.storyboard.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.link.linkagent.creator.creation.storyboard.mapper.StoryboardMapper;
import com.link.linkagent.creator.creation.storyboard.model.StoryboardAudioModels.*;
import com.link.linkagent.creator.creation.storyboard.model.StoryboardModels.*;
import com.link.linkagent.creator.media.upload.mapper.MediaUploadMapper;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 使用真实业务编译器核验音轨范围，持久化事务与真实数据库由独立集成测试覆盖。 */
class StoryboardAudioServiceTest {
    static final ValidatorFactory VALIDATION=Validation.buildDefaultValidatorFactory();
    final ObjectMapper json=new ObjectMapper();
    final StoryboardMapper mapper=mock(StoryboardMapper.class);
    final StoryboardPersistence persistence=mock(StoryboardPersistence.class);
    final MediaUploadMapper media=mock(MediaUploadMapper.class);
    final StoryboardCodec codec=new StoryboardCodec(json,VALIDATION.getValidator());
    final StoryboardCompiler compiler=new StoryboardCompiler(json);
    final StoryboardAudioService service=new StoryboardAudioService(mapper,persistence,codec,compiler,media,json);
    Board board;Brief brief;Candidate candidate;List<Asset> assets;
    final String srt="1\n00:00:00,000 --> 00:00:01,000\n用户已有字幕\n\n2\n00:00:01,000 --> 00:00:02,000\n第二句";

    @AfterAll static void closeValidation(){VALIDATION.close();}
    @BeforeEach void setup() {
        when(media.countTaskByOwner("task","default")).thenReturn(1);
        when(mapper.briefRevision("task","default")).thenReturn(1);
        when(mapper.boardByKey(eq("task"),eq("default"),anyString())).thenReturn(Optional.empty());
        configure(List.of(scene("s1",2000,"NARRATION","voice",List.of())),List.of(voice(2)),2000,"USER_NARRATION");
        when(persistence.saveWithRequestHash(eq("task"),eq("default"),anyString(),any(),anyString()))
                .thenAnswer(call->new Board("child","task","default",2,"brief","board",null,
                        call.getArgument(2),call.getArgument(4),"hash","{}","[]",null));
    }

    @Test void bomAndCrLfImportCreatesChildAndPreservesRawRequestFingerprint() {
        String originalJson=board.documentJson();String raw="\uFEFF"+srt.replace("\n","\r\n");
        SubtitleImport input=new SubtitleImport(17L,"s1",raw);
        Board result=service.importSubtitles("task","default","key","board",input);
        var saved=ArgumentCaptor.forClass(Save.class);var key=ArgumentCaptor.forClass(String.class);var hash=ArgumentCaptor.forClass(String.class);
        verify(persistence).saveWithRequestHash(eq("task"),eq("default"),key.capture(),saved.capture(),hash.capture());
        assertThat(result.storyboardId()).isEqualTo("child");assertThat(saved.getValue().expectedWorkspaceRevision()).isEqualTo(17);
        assertThat(saved.getValue().parentStoryboardId()).isEqualTo("board");assertThat(saved.getValue().briefId()).isEqualTo("brief");
        assertThat(saved.getValue().candidate().scenes().getFirst().cues())
                .containsExactly(new Cue(0,1000,"用户已有字幕"),new Cue(1000,2000,"第二句"));
        assertThat(key.getValue()).isEqualTo("subtitles-"+StoryboardCodec.digest("key"));
        assertThat(hash.getValue()).isEqualTo(codec.hash(Map.of("storyboardId","board","input",input)));
        assertThat(hash.getValue()).isNotEqualTo(codec.hash(Map.of("storyboardId","board","input",new SubtitleImport(17L,"s1",srt))));
        assertThat(board.documentJson()).isEqualTo(originalJson);
    }

    @Test void otherScenesMayRemainUnpreparedWhileSelectedSceneReceivesValidCues() {
        configure(List.of(scene("s1",2000,"NARRATION","voice",List.of()),scene("s2",2000,"NARRATION",null,List.of())),List.of(voice(2)),4000,"USER_NARRATION");
        assertThat(service.importSubtitles("task","default","key","board",new SubtitleImport(0L,"s1",srt)).storyboardId()).isEqualTo("child");
        verify(persistence).saveWithRequestHash(anyString(),anyString(),anyString(),any(),anyString());
    }

    @ParameterizedTest @ValueSource(strings={
            "00:00:00,000 --> 00:00:01,000\n缺少编号",
            "1\n00:00:00.000 --> 00:00:01.000\n错误时间",
            "1\n00:60:00,000 --> 00:60:01,000\n错误分钟",
            "1\n00:00:01,000 --> 00:00:00,000\n反向",
            "1\n00:00:00,000 --> 00:00:01,500\n一句\n\n2\n00:00:01,000 --> 00:00:02,000\n重叠",
            "1\n00:00:00,000 --> 00:00:01,000\n一句\n\n1\n00:00:01,000 --> 00:00:02,000\n重复编号",
            "1\n00:00:00,000 --> 00:00:01,000\n含\t制表符",
            "1\n00:00:00,000 --> 00:00:01,000\n"
    }) void malformedSubtitlesNeverReachPersistence(String invalid) {
        assertThatThrownBy(()->service.importSubtitles("task","default","key","board",new SubtitleImport(0L,"s1",invalid)))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        verify(persistence,never()).saveWithRequestHash(anyString(),anyString(),anyString(),any(),anyString());
    }

    @Test void cueBeyondFrozenActualAudioOrSceneIsRejected() {
        String overflow="1\n00:00:00,000 --> 00:00:02,001\n不能越界";
        assertThatThrownBy(()->service.importSubtitles("task","default","key","board",new SubtitleImport(0L,"s1",overflow))).hasMessageContaining("真实音轨");
        configure(List.of(scene("s1",2000,"NARRATION","voice",List.of())),List.of(),2000,"USER_NARRATION");
        assertThatThrownBy(()->service.importSubtitles("task","default","key","board",new SubtitleImport(0L,"s1",srt))).hasMessageContaining("真实音轨");
    }

    @Test void byteAndCueCountAndTextLimitsAreEnforcedBeforeSaving() {
        assertThatThrownBy(()->service.importSubtitles("task","default","key","board",new SubtitleImport(0L,"s1","中".repeat(22000)))).hasMessageContaining("64 KiB");
        String tooMany=java.util.stream.IntStream.rangeClosed(1,101).mapToObj(i->i+"\n00:00:00,000 --> 00:00:00,001\n字").collect(java.util.stream.Collectors.joining("\n\n"));
        assertThatThrownBy(()->service.importSubtitles("task","default","key","board",new SubtitleImport(0L,"s1",tooMany))).hasMessageContaining("100");
        assertThatThrownBy(()->service.importSubtitles("task","default","key","board",new SubtitleImport(0L,"s1","1\n00:00:00,000 --> 00:00:01,000\n"+"字".repeat(121)))).hasMessageContaining("120");
        verify(persistence,never()).saveWithRequestHash(anyString(),anyString(),anyString(),any(),anyString());
    }

    @Test void combinedSubtitleCapacityIncludesOtherScenes() {
        List<Cue> hundred=java.util.stream.IntStream.range(0,100).mapToObj(i->new Cue(i*10,i*10+10,"字")).toList();
        configure(List.of(scene("s1",2000,"NARRATION","voice",List.of()),scene("s2",2000,"NARRATION","voice",hundred),scene("s3",2000,"NARRATION","voice",hundred)),List.of(voice(2)),6000,"USER_NARRATION");
        assertThatThrownBy(()->service.importSubtitles("task","default","key","board",new SubtitleImport(0L,"s1",srt))).hasMessageContaining("200");
    }

    @Test void foreignOrDeletedTaskAndForeignBoardAreRejected() {
        assertThatThrownBy(()->service.importSubtitles("other","default","key","board",new SubtitleImport(0L,"s1",srt))).hasMessageContaining("任务不存在");
        assertThatThrownBy(()->service.timing("other","default","board")).hasMessageContaining("任务不存在");
        when(mapper.board("task","default","board")).thenReturn(Optional.empty());
        assertThatThrownBy(()->service.importSubtitles("task","default","key","board",new SubtitleImport(0L,"s1",srt))).hasMessageContaining("不属于本任务");
    }

    @Test void silentAndMissingSceneCannotReceiveAudioSubtitles() {
        assertThatThrownBy(()->service.importSubtitles("task","default","key","board",new SubtitleImport(0L,"missing",srt))).hasMessageContaining("场景不存在");
        configure(List.of(scene("s1",2000,"NO_DIALOGUE",null,List.of())),List.of(),2000,"NO_DIALOGUE");
        assertThatThrownBy(()->service.importSubtitles("task","default","key","board",new SubtitleImport(0L,"s1",srt))).hasMessageContaining("只有实际录音");
    }

    @Test void replayChecksOriginalRawPayloadAndNeedsNoCurrentBrief() {
        SubtitleImport input=new SubtitleImport(0L,"s1",srt);String key="subtitles-"+StoryboardCodec.digest("key");
        var saved=new Board("saved","task","default",2,"brief","board",null,key,
                codec.hash(Map.of("storyboardId","board","input",input)),"hash","{}","[]",null);
        when(mapper.boardByKey("task","default",key)).thenReturn(Optional.of(saved));
        when(mapper.briefRevision("task","default")).thenReturn(5);
        assertThat(service.importSubtitles("task","default","key","board",input)).isEqualTo(saved);
        assertThatThrownBy(()->service.importSubtitles("task","default","key","board",new SubtitleImport(0L,"s1",srt+"\n"))).hasMessageContaining("不同请求");
        verify(persistence,never()).saveWithRequestHash(anyString(),anyString(),anyString(),any(),anyString());
    }

    @Test void threeSecondRecordingInTwoSecondSceneOffersChoicesWithoutSaving() {
        configure(List.of(scene("s1",2000,"NARRATION","voice",List.of())),List.of(voice(3)),2000,"USER_NARRATION");
        var report=service.timing("task","default","board");var first=report.scenes().getFirst();
        assertThat(first.audioDurationMs()).isEqualTo(3000);assertThat(first.sceneDurationMs()).isEqualTo(2000);assertThat(first.differenceMs()).isEqualTo(1000);
        assertThat(first.proposals()).extracting(TimingProposal::code).containsExactly("EXTEND_SCENE","RECORD_AGAIN");
        assertThat(first.proposals().getFirst().proposedDurationMs()).isEqualTo(3000);assertThat(report.requiresUserDecision()).isTrue();
        verify(persistence,never()).saveWithRequestHash(anyString(),anyString(),anyString(),any(),anyString());
    }

    @Test void timingUsesFrameQuantizationAndOffersTotalMismatchExplicitly() {
        configure(List.of(scene("s1",1001,"NO_DIALOGUE",null,List.of()),scene("s2",1001,"NO_DIALOGUE",null,List.of())),List.of(),5000,"NO_DIALOGUE");
        var report=service.timing("task","default","board");
        assertThat(report.quantizedDurationMs()).isEqualTo(2000);assertThat(report.differenceMs()).isEqualTo(-3000);
        assertThat(report.proposals()).extracting(TimingProposal::code).containsExactly("CONFIRM_TOTAL_DURATION");
    }

    @Test void shortRecordingOffersOptionalShorteningAndTtsNeverUsesTextLength() {
        configure(List.of(scene("s1",2000,"NARRATION","voice",List.of())),List.of(voice(1)),2000,"USER_NARRATION");
        assertThat(service.timing("task","default","board").proposals()).extracting(TimingProposal::code).containsExactly("SHORTEN_SCENE");
        configure(List.of(scene("s1",2000,"TTS",null,List.of())),List.of(),2000,"TTS");
        var tts=service.timing("task","default","board").scenes().getFirst();
        assertThat(tts.audioDurationMs()).isNull();assertThat(tts.timingSource()).isEqualTo("NOT_GENERATED");
        assertThat(tts.proposals()).extracting(TimingProposal::code).containsExactly("PREPARE_REAL_AUDIO");
    }

    @Test void originalVideoSrtMustFitRealSelectedSourceAudio() {
        var original=new Scene("s1","VIDEO_CLIP","原声片段",List.of(),"",2000,"video",1000L,3000L,"CONTAIN","ORIGINAL",null,List.of(),List.of("brief:user"));
        var meta=json.createObjectNode();var streams=meta.putObject("normalized").putArray("streams");
        streams.addObject().put("codec_type","video").put("codec_name","h264").put("duration","4.0").put("width",320).put("height",180).put("start_time","0");
        streams.addObject().put("codec_type","audio").put("codec_name","aac").put("duration","2.0").put("start_time","1.5");
        var video=new Asset("video","VIDEO_CLIP","private","video.mp4","a".repeat(64),100,"video/mp4",meta);
        configure(List.of(original),List.of(video),2000,"SOURCE");
        assertThatThrownBy(()->service.importSubtitles("task","default","key","board",new SubtitleImport(0L,"s1",srt))).hasMessageContaining("真实音轨");
        String valid="1\n00:00:00,500 --> 00:00:01,500\n视频的真实原声";
        assertThat(service.importSubtitles("task","default","key","board",new SubtitleImport(0L,"s1",valid)).storyboardId()).isEqualTo("child");
        var timing=service.timing("task","default","board").scenes().getFirst();
        assertThat(timing.audioDurationMs()).isEqualTo(1500);assertThat(timing.proposals()).extracting(TimingProposal::code).contains("CONFIRM_SOURCE_SILENCE");
    }

    @Test void silentVideoBeyondItsActualPictureDurationRequiresMoreMedia() {
        var videoScene=new Scene("s1","VIDEO_CLIP","静音片段",List.of(),"",2000,"video",1000L,3000L,"CONTAIN","NO_DIALOGUE",null,List.of(),List.of("brief:user"));
        var meta=json.createObjectNode();meta.putObject("normalized").putArray("streams").addObject()
                .put("codec_type","video").put("duration","2.0");
        configure(List.of(videoScene),List.of(new Asset("video","VIDEO_CLIP","private","video.mp4","a".repeat(64),100,"video/mp4",meta)),2000,"NO_DIALOGUE");
        assertThat(service.timing("task","default","board").proposals()).extracting(TimingProposal::code).containsExactly("SUPPLY_MORE_VIDEO");
    }

    @Test void timelineApplyAcceptsOnlyTheExactMillisecondsOfARealProposal() {
        configure(List.of(scene("s1",2000,"NARRATION","voice",List.of())),List.of(voice(3)),2000,"USER_NARRATION");
        var report=service.timing("task","default","board");
        assertThat(report.scenes().getFirst().proposals().getFirst().proposedDurationMs()).isEqualTo(3000);
        assertThatThrownBy(()->service.applyTimeline("task","default","key","board",
                new TimelineApply(0L,List.of(new TimelineAdjustment("s1","EXTEND_SCENE",2800)))))
                .hasMessageContaining("采纳时长必须与当前时长提案给出的毫秒数一致");
        assertThatThrownBy(()->service.applyTimeline("task","default","key","board",
                new TimelineApply(0L,List.of(new TimelineAdjustment("s1","EXTEND_SCENE",3100)))))
                .hasMessageContaining("采纳时长必须与当前时长提案给出的毫秒数一致");
        assertThatThrownBy(()->service.applyTimeline("task","default","key","board",
                new TimelineApply(0L,List.of(new TimelineAdjustment("s1","SHORTEN_SCENE",2000)))))
                .hasMessageContaining("当前不存在 SHORTEN_SCENE 提案");
        verify(persistence,never()).saveTimeline(anyString(),anyString(),anyString(),anyString(),anyString(),
                anyLong(),any(),any(),anyString(),anySet(),anyList());
    }

    @Test void timelineApplyRejectsDuplicateScenesAndUnsupportedProposalCodes() {
        configure(List.of(scene("s1",2000,"NARRATION","voice",List.of())),List.of(voice(3)),2000,"USER_NARRATION");
        assertThatThrownBy(()->service.applyTimeline("task","default","key","board",
                new TimelineApply(0L,List.of(new TimelineAdjustment("s1","EXTEND_SCENE",3000),
                        new TimelineAdjustment("s1","EXTEND_SCENE",3000)))))
                .hasMessageContaining("同一场景不能在一次请求中重复调整");
        // RECORD_AGAIN/SUPPLY_MORE_VIDEO 需要重录或补素材，不能靠改时长自动满足。
        assertThatThrownBy(()->service.applyTimeline("task","default","key","board",
                new TimelineApply(0L,List.of(new TimelineAdjustment("s1","RECORD_AGAIN",2000)))))
                .hasMessageContaining("故事板字段");
        verify(persistence,never()).saveTimeline(anyString(),anyString(),anyString(),anyString(),anyString(),
                anyLong(),any(),any(),anyString(),anySet(),anyList());
    }

    @Test void timelineApplyReplayRejectsDifferentRequest() {
        var input=new TimelineApply(0L,List.of(new TimelineAdjustment("s1","EXTEND_SCENE",3000)));
        String key="timeline-"+StoryboardCodec.digest("key");
        var saved=new Board("child","task","default",2,"brief-2","board",null,key,
                codec.hash(Map.of("storyboardId","board","input",input)),"hash","{}","[]",null);
        when(mapper.boardByKey("task","default",key)).thenReturn(Optional.of(saved));
        assertThatThrownBy(()->service.applyTimeline("task","default","key","board",
                new TimelineApply(0L,List.of(new TimelineAdjustment("s1","SHORTEN_SCENE",1000)))))
                .hasMessageContaining("同一时间线幂等键对应不同请求");
        verify(persistence,never()).saveTimeline(anyString(),anyString(),anyString(),anyString(),anyString(),
                anyLong(),any(),any(),anyString(),anySet(),anyList());
    }

    private void configure(List<Scene> scenes,List<Asset> frozen,long target,String mode) {        candidate=new Candidate(scenes);assets=frozen;ObjectNode document=json.createObjectNode();document.set("candidate",json.valueToTree(candidate));
        board=new Board("board","task","default",1,"brief",null,null,"original","original-hash","document-hash",document.toString(),"[]",null);
        ObjectNode briefDocument=json.createObjectNode().put("targetDurationMs",target).put("dialogueMode",mode);
        brief=new Brief("brief","task","default",1,"brief-key","hash",briefDocument.toString(),"{}",null);
        when(mapper.board("task","default","board")).thenReturn(Optional.of(board));
        when(persistence.brief("task","default","brief")).thenReturn(brief);when(persistence.frozen(board)).thenReturn(frozen);
    }
    private Scene scene(String id,long duration,String mode,String voice,List<Cue> cues) {
        return new Scene(id,"TITLE_CARD","真实输入",List.of("画面说明"),"文稿",duration,null,null,null,"CONTAIN",mode,voice,cues,List.of("brief:user"));
    }
    private Asset voice(double seconds) {
        var meta=json.createObjectNode();var normalized=meta.putObject("normalized");normalized.putObject("format").put("duration",Double.toString(seconds));
        normalized.putArray("streams").addObject().put("codec_type","audio").put("codec_name","pcm_s16le").put("duration",Double.toString(seconds)).put("sample_rate","48000").put("channels",2);
        return new Asset("voice","NARRATION","private","voice.wav","b".repeat(64),100,"audio/wav",meta);
    }
}
