package com.link.linkagent.creator.creation.storyboard.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.link.linkagent.creator.creation.revision.RevisionInterpreter;
import com.link.linkagent.creator.creation.storyboard.model.StoryboardModels.Scene;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class RevisionInterpreterTest {
    private final RevisionInterpreter parser=new RevisionInterpreter(new ObjectMapper());
    private final List<Scene> scenes=List.of(scene("first"),scene("second"),scene("third"));
    private static Scene scene(String id){return new Scene(id,"TITLE_CARD","标题",List.of("正文"),"原文",5000,null,null,null,"CONTAIN","TTS",null,List.of(),List.of("brief:user"));}
    @Test void acceptsChineseOrdinalsAndKeepsPunctuationInsideQuotedNarration(){
        var result=parser.interpret("第三幕旁白改为“新的文稿，保留这个逗号”；字幕大一点",scenes,List.of(),36);
        assertThat(result.clarifications()).isEmpty();assertThat(result.changes()).hasSize(2);
        assertThat(result.changes().getFirst().sceneId()).isEqualTo("third");assertThat(result.changes().getFirst().value().asText()).isEqualTo("新的文稿，保留这个逗号");
    }
    @Test void unsupportedPartPreventsPartialExecution(){
        var result=parser.interpret("字幕大一点，让它更有电影感",scenes,List.of(),36);
        assertThat(result.changes()).isEmpty();assertThat(result.clarifications()).hasSize(1);
    }
    @Test void missingAssetIsAClarificationInsteadOfAnInventedSelection(){
        var result=parser.interpret("第三幕换成未知图片.png",scenes,List.of(),36);
        assertThat(result.changes()).isEmpty();assertThat(result.clarifications().getFirst()).contains("未知图片.png");
    }
    @Test void movingEarlierSceneBeforeLaterSceneUsesPostRemovalPosition(){
        var result=parser.interpret("第一幕移到第三幕前面",scenes,List.of(),36);
        assertThat(result.changes().getFirst().value().asInt()).isEqualTo(1);
    }
    @Test void unpairedQuotesAndOversizedNumbersDoNotBecomeExecutableChanges(){
        assertThat(parser.interpret("第三幕旁白改为“未结束",scenes,List.of(),36).clarifications()).isNotEmpty();
        assertThat(parser.interpret("字幕字号设为999999999999999",scenes,List.of(),36).changes()).isEmpty();
    }
    @Test void musicAndVoiceChangesUseTheExplicitFileWithoutSelectingAnotherAsset(){
        var music=new com.link.linkagent.creator.creation.asset.model.CreationAsset();music.setAssetId("music");music.setKind("MUSIC");music.setStatus("READY");music.setFileName("音乐.wav");
        var result=parser.interpret("背景音乐换成“音乐.wav”；第三幕声音改为无对白；第一幕画面适配改为完整显示",scenes,List.of(music),36);
        assertThat(result.clarifications()).isEmpty();assertThat(result.changes()).extracting(c->c.operation()).containsExactly("SET_BGM","SET_AUDIO","SET_FIT");
        assertThat(result.changes().getFirst().value().path("assetId").asText()).isEqualTo("music");assertThat(result.changes().getFirst().value().path("sourceOutMs").asLong()).isEqualTo(15000);
        assertThat(parser.interpret("背景音乐音量改为-20dB",scenes,List.of(),36).clarifications()).isNotEmpty();
        assertThat(parser.interpret("第二十三四幕声音改为无对白",scenes,List.of(),36).changes()).isEmpty();
    }
    @Test void replacingMusicThenChangingVolumeKeepsTheNewMusicAndIndependentChanges(){
        var brief=new ObjectMapper().createObjectNode().put("targetDurationMs",15000);
        var original=brief.putObject("backgroundMusic").put("assetId","original").put("gainDb",-18).put("fadeInMs",400);
        var result=parser.interpret("背景音乐换成音乐.wav，背景音乐音量改为-12dB",scenes,List.of(music()),36,brief);
        assertThat(result.clarifications()).isEmpty();assertThat(result.changes()).hasSize(2);
        assertThat(result.changes().getLast().value().path("assetId").asText()).isEqualTo("replacement");
        assertThat(result.changes().getLast().value().path("gainDb").asInt()).isEqualTo(-12);
        assertThat(result.changes().getFirst().value().path("gainDb").asInt()).isEqualTo(-18);
        assertThat(original.path("assetId").asText()).isEqualTo("original");
        assertThat(original.path("gainDb").asInt()).isEqualTo(-18);
    }
    @Test void addingMusicThenChangingVolumeWorksWithoutAnExistingMusicBed(){
        var result=parser.interpret("背景音乐换成音乐.wav，背景音乐音量改为-12dB",scenes,List.of(music()),36);
        assertThat(result.clarifications()).isEmpty();
        assertThat(result.changes().getLast().value().path("assetId").asText()).isEqualTo("replacement");
        assertThat(result.changes().getLast().value().path("gainDb").asInt()).isEqualTo(-12);
    }
    @Test void changingVolumeThenReplacingMusicPreservesTheUpdatedVolume(){
        var brief=new ObjectMapper().createObjectNode().put("targetDurationMs",15000);
        brief.putObject("backgroundMusic").put("assetId","original").put("gainDb",-18);
        var result=parser.interpret("背景音乐音量改为-12dB，背景音乐换成音乐.wav",scenes,List.of(music()),36,brief);
        assertThat(result.clarifications()).isEmpty();
        assertThat(result.changes().getLast().value().path("assetId").asText()).isEqualTo("replacement");
        assertThat(result.changes().getLast().value().path("gainDb").asInt()).isEqualTo(-12);
        assertThat(result.changes().getFirst().value().path("assetId").asText()).isEqualTo("original");
    }
    @Test void removingMusicResetsItsSettingsAndRequiresAFileBeforeAnotherVolumeChange(){
        var brief=new ObjectMapper().createObjectNode().put("targetDurationMs",15000);
        brief.putObject("backgroundMusic").put("assetId","original").put("gainDb",-6).put("fadeInMs",900);
        var replacement=parser.interpret("取消背景音乐，背景音乐换成音乐.wav",scenes,List.of(music()),36,brief);
        assertThat(replacement.clarifications()).isEmpty();
        assertThat(replacement.changes().getFirst().value().isNull()).isTrue();
        assertThat(replacement.changes().getLast().value().path("gainDb").asInt()).isEqualTo(-18);
        assertThat(replacement.changes().getLast().value().path("fadeInMs").asInt()).isEqualTo(200);
        var missing=parser.interpret("取消背景音乐，背景音乐音量改为-12dB",scenes,List.of(music()),36,brief);
        assertThat(missing.clarifications()).hasSize(1);assertThat(missing.changes()).isEmpty();
    }
    private static com.link.linkagent.creator.creation.asset.model.CreationAsset music(){
        var music=new com.link.linkagent.creator.creation.asset.model.CreationAsset();
        music.setAssetId("replacement");music.setKind("MUSIC");music.setStatus("READY");music.setFileName("音乐.wav");return music;
    }
}
