package com.link.linkagent.creator.creation.execution;

import com.link.linkagent.creator.creation.execution.ExecutionModels.*;
import com.link.linkagent.creator.creation.revision.*;
import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.List;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 在真实 Spring 路由与参数绑定层验证前端使用的 URL，不启动后端或访问供应商。 */
class CreationExecutionApiTest {
    private final ExecutionService executions=mock(ExecutionService.class);
    private final ExecutionDownloads downloads=mock(ExecutionDownloads.class);
    private final RevisionService revisions=mock(RevisionService.class);
    private MockMvc mvc;
    private static final String ROOT="/api/creator/tasks/task-1/creation";
    @BeforeEach void setup(){mvc=MockMvcBuilders.standaloneSetup(new ExecutionController(executions,downloads),new RevisionController(revisions)).build();}
    @Test void estimateUsesTheSameActionPathAsTheFrontend()throws Exception {
        mvc.perform(post(ROOT+"/executions:estimate").contentType("application/json").content("{\"expectedWorkspaceRevision\":2,\"storyboardId\":\"board-1\",\"kind\":\"SAMPLE\"}"))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));
        verify(executions).estimate(eq("task-1"),eq("default"),any(EstimateInput.class));
    }
    @Test void proposeUsesTheSameActionPathAsTheFrontend()throws Exception {
        when(revisions.propose(anyString(),anyString(),anyString(),any())).thenReturn(new RevisionModels.View(null,"board-1",2,List.of(),null,List.of("需要明确场景"),null));
        mvc.perform(post(ROOT+"/revisions:propose").header("Idempotency-Key","proposal-1").contentType("application/json").content("{\"expectedWorkspaceRevision\":2,\"storyboardId\":\"board-1\",\"instruction\":\"字幕大一点\"}"))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.clarifications[0]").value("需要明确场景"));
        verify(revisions).propose(eq("task-1"),eq("default"),eq("proposal-1"),any(RevisionModels.Propose.class));
    }
    @Test void startRequiresTheConfirmedConfigurationAndAnIdempotencyKey()throws Exception {
        String body="{\"expectedWorkspaceRevision\":2,\"storyboardId\":\"board-1\",\"storyboardHash\":\""+"a".repeat(64)+"\",\"kind\":\"SAMPLE\",\"maxAdditionalCostCny\":1}";
        mvc.perform(post(ROOT+"/executions").header("Idempotency-Key","start-1").contentType("application/json").content(body)).andExpect(status().isBadRequest());
        verifyNoInteractions(executions);
        String valid=body.substring(0,body.length()-1)+",\"providerFingerprint\":\""+"b".repeat(64)+"\"}";
        mvc.perform(post(ROOT+"/executions").contentType("application/json").content(valid)).andExpect(status().isBadRequest());
        mvc.perform(post(ROOT+"/executions").header("Idempotency-Key","start-1").contentType("application/json").content(valid))
            .andExpect(status().isAccepted()).andExpect(header().string("Cache-Control","no-store"));
        verify(executions).start(eq("task-1"),eq("default"),eq("start-1"),any(Start.class));
    }
    @Test void recordsCancellationDownloadsAndRevisionApplyKeepTaskOwnership()throws Exception {
        when(executions.list("task-1","default")).thenReturn(List.of());
        mvc.perform(get(ROOT+"/executions")).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));
        mvc.perform(get(ROOT+"/executions/execution-1")).andExpect(status().isOk());
        mvc.perform(post(ROOT+"/executions/execution-1:cancel")).andExpect(status().isOk());
        mvc.perform(get(ROOT+"/executions/execution-1/downloads/video.mp4")).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));
        mvc.perform(post(ROOT+"/revisions/proposal-1:apply").contentType("application/json").content("{\"expectedWorkspaceRevision\":2}")).andExpect(status().isOk());
        verify(executions).get("task-1","default","execution-1");verify(executions).cancel("task-1","default","execution-1");
        verify(downloads).download("task-1","default","execution-1","video.mp4");verify(revisions).apply(eq("task-1"),eq("default"),eq("proposal-1"),any(RevisionModels.Apply.class));
    }
}
