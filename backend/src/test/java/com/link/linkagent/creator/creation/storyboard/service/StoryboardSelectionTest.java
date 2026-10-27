package com.link.linkagent.creator.creation.storyboard.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.link.linkagent.creator.creation.storyboard.model.StoryboardModels.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class StoryboardSelectionTest {
    private Scene scene(String id,long ms){return new Scene(id,"TITLE_CARD","标题",List.of("正文"),"",ms,null,null,null,"CONTAIN","NO_DIALOGUE",null,List.of(),List.of("brief:user"));}
    @Test void sampleUsesWholeScenesAndDoesNotIncludeUncoveredLongScenes(){
        var candidate=new Candidate(List.of(scene("first",8000),scene("second",6000),scene("third",9000)));
        assertThat(StoryboardSelection.sample(candidate)).containsExactly("first","second");
        assertThat(StoryboardSelection.sample(new Candidate(List.of(scene("long",30000),scene("next",5000))))).containsExactly("long");
    }
    @Test void fullScopeDoesNotRewriteUsersTargetAndSampleDoesNotMutateMusic(){
        var json=new ObjectMapper();var brief=json.createObjectNode().put("targetDurationMs",90000);
        brief.putObject("backgroundMusic").put("sourceInMs",2000).put("sourceOutMs",92000).put("fadeInMs",8000).put("fadeOutMs",8000);
        var source=new Candidate(List.of(scene("first",5000),scene("next",5000)));
        assertThat(StoryboardSelection.scope(brief,source,null).brief().path("targetDurationMs").asLong()).isEqualTo(90000);
        var scope=StoryboardSelection.scope(brief,source,List.of("first"));
        assertThat(scope.brief().path("targetDurationMs").asLong()).isEqualTo(5000);
        assertThat(scope.brief().path("backgroundMusic").path("sourceOutMs").asLong()).isEqualTo(7000);
        assertThat(brief.path("backgroundMusic").path("sourceOutMs").asLong()).isEqualTo(92000);
    }
    @Test void duplicateUnknownAndEmptySelectionAreRejected(){
        var scenes=List.of(scene("first",5000));
        for(var ids:List.of(List.<String>of(),List.of("first","first"),List.of("missing")))
            assertThatThrownBy(()->StoryboardSelection.select(scenes,ids)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
    @Test void preparationMayLeaveUnrelatedMissingAssetsButCannotIntroduceNewFailures(){
        var before=new Check(false,List.of(new Problem("a","TTS_AUDIO_MISSING",""),new Problem("a","AI_VIDEO_ASSET_MISSING","")),5000,null);
        var after=new Check(false,List.of(new Problem("a","AI_VIDEO_ASSET_MISSING","")),5000,null);
        assertThat(StoryboardSelection.prepared(before,after,Set.of("a"),Set.of("TTS_","AUDIO_"))).isTrue();
        assertThat(StoryboardSelection.prepared(before,new Check(false,List.of(new Problem("a","CUE_OUT_OF_RANGE","")),5000,null),Set.of("a"),Set.of("TTS_"))).isFalse();
        assertThat(StoryboardSelection.prepared(before,before,Set.of("a"),Set.of("TTS_"))).isFalse();
    }
}
