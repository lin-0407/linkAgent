package com.link.linkagent.creator.creation.storyboard;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.link.linkagent.creator.creation.storyboard.controller.StoryboardController;
import com.link.linkagent.creator.creation.storyboard.model.StoryboardModels.*;
import com.link.linkagent.creator.creation.storyboard.service.StoryboardService;
import com.link.linkagent.creator.creation.storyboard.service.StoryboardAudioService;
import com.link.linkagent.creator.creation.storyboard.model.StoryboardAudioModels.*;
import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import java.util.Map;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class StoryboardControllerTest {
    StoryboardService service;StoryboardAudioService audio;MockMvc mvc;LocalValidatorFactoryBean validator;
    @BeforeEach void setup(){service=mock(StoryboardService.class);audio=mock(StoryboardAudioService.class);validator=new LocalValidatorFactoryBean();validator.afterPropertiesSet();mvc=MockMvcBuilders.standaloneSetup(new StoryboardController(service,audio)).setValidator(validator).build();}
    @AfterEach void close(){validator.close();}
    @Test void preparationNeedsExplicitBudgetAndReturnsAcceptedWithoutOpeningFullRun() throws Exception {
        when(service.generate(anyString(),anyString(),anyString(),any())).thenReturn(Map.of("fullProductionAvailable",false));
        mvc.perform(post("/api/creator/tasks/task-1/creation/storyboards:generate").header("Idempotency-Key","prepare-1").contentType("application/json")
            .content("{\"expectedWorkspaceRevision\":2,\"briefId\":\"brief-1\",\"maxAdditionalCostCny\":0.02}"))
            .andExpect(status().isAccepted()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.fullProductionAvailable").value(false));
        verify(service).generate(eq("task-1"),eq("default"),eq("prepare-1"),any(Generate.class));
        mvc.perform(post("/api/creator/tasks/task-1/creation/runs").contentType("application/json").content("{}")).andExpect(status().isNotFound());
    }
    @Test void malformedBriefAndMissingGenerationAuthorizationAreRejectedBeforeService() throws Exception {
        mvc.perform(post("/api/creator/tasks/task-1/creation/briefs").header("Idempotency-Key","bad").contentType("application/json")
            .content("{\"expectedWorkspaceRevision\":0,\"topic\":\"\",\"coreMessage\":\"演示\",\"audience\":\"新手\",\"targetDurationMs\":0,\"dialogueMode\":\"NO_DIALOGUE\",\"contentKind\":\"USER_PROVIDED\",\"assetIds\":[null],\"constraints\":\"\"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/creator/tasks/task-1/creation/storyboards:generate").header("Idempotency-Key","bad").contentType("application/json")
            .content("{\"expectedWorkspaceRevision\":0,\"briefId\":\"brief-1\"}" )).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
    @Test void estimationDoesNotRequirePaymentAuthorizationOrIdempotencyKey() throws Exception {
        when(service.estimate(eq("task-1"),eq("default"),any(EstimateInput.class)))
                .thenReturn(Map.of("estimatedMaxAdditionalCostCny",0.05,"basis","CONFIGURED_ESTIMATE"));
        mvc.perform(post("/api/creator/tasks/task-1/creation/storyboards:estimate").contentType("application/json")
                .content("{\"expectedWorkspaceRevision\":2,\"briefId\":\"brief-1\"}"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$.basis").value("CONFIGURED_ESTIMATE"));
        verify(service).estimate("task-1","default",new EstimateInput(2L,"brief-1"));
        verify(service,never()).generate(anyString(),anyString(),anyString(),any());
    }
    @Test void sceneAssetPathsAndNullScenesAreRejected() throws Exception {
        var json=new ObjectMapper();
        String scene="{\"id\":\"scene-1\",\"layout\":\"IMAGE_CARD\",\"title\":\"标题\",\"body\":[],\"narration\":\"\",\"durationMs\":1000,\"visualAssetId\":\"file:///tmp/p.png\",\"fit\":\"CONTAIN\",\"audioMode\":\"NO_DIALOGUE\",\"cues\":[],\"sourceRefs\":[\"brief:user\"]}";
        for(String candidate:new String[]{"{\"scenes\":[null]}","{\"scenes\":["+scene+"]}"}) {
            var body=json.createObjectNode().put("expectedWorkspaceRevision",0).put("briefId","brief-1");body.set("candidate",json.readTree(candidate));
            mvc.perform(post("/api/creator/tasks/task-1/creation/storyboards").header("Idempotency-Key","bad").contentType("application/json").content(body.toString())).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(service);
    }
    @Test void subtitleImportNeedsRevisionAndReturnsNewBoardWhileTimingIsReadOnly() throws Exception {
        var saved=mock(Board.class);when(saved.storyboardId()).thenReturn("board-2");
        when(audio.importSubtitles(anyString(),anyString(),anyString(),anyString(),any())).thenReturn(saved);
        mvc.perform(post("/api/creator/tasks/task-1/creation/storyboards/board-1/subtitles:import")
                .header("Idempotency-Key","srt-1").contentType("application/json")
                .content("{\"expectedWorkspaceRevision\":2,\"sceneId\":\"opening\",\"srt\":\"1\\n00:00:00,100 --> 00:00:00,900\\n用户字幕\"}"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));
        verify(service).board("task-1","default","board-2");
        mvc.perform(get("/api/creator/tasks/task-1/creation/storyboards/board-1/timing"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));
        verify(audio).timing("task-1","default","board-1");
        mvc.perform(post("/api/creator/tasks/task-1/creation/storyboards/board-1/subtitles:import")
                .header("Idempotency-Key","bad").contentType("application/json")
                .content("{\"sceneId\":\"opening\",\"srt\":\"字幕\"}")).andExpect(status().isBadRequest());
        verify(audio,times(1)).importSubtitles(anyString(),anyString(),anyString(),anyString(),any());
    }
}
