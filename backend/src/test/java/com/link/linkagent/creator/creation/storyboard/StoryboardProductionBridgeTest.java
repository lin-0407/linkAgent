package com.link.linkagent.creator.creation.storyboard;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.link.linkagent.creator.creation.mapper.*;
import com.link.linkagent.creator.creation.model.*;
import com.link.linkagent.creator.creation.model.CreationRuntimeModels.*;
import com.link.linkagent.creator.creation.render.*;
import com.link.linkagent.creator.creation.service.*;
import com.link.linkagent.creator.creation.storyboard.mapper.StoryboardMapper;
import com.link.linkagent.creator.creation.storyboard.model.StoryboardModels.*;
import com.link.linkagent.creator.creation.storyboard.service.*;
import com.link.linkagent.creator.media.upload.mapper.MediaUploadMapper;
import org.junit.jupiter.api.*;
import java.nio.file.Path;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 此处验证授权与丢响应清理分支，实际编译/下载/渲染由独立原生套件验证。 */
class StoryboardProductionBridgeTest {
    StoryboardMapper boards=mock(StoryboardMapper.class);StoryboardPersistence persistence=mock(StoryboardPersistence.class);StoryboardCodec codec=mock(StoryboardCodec.class);
    StoryboardCompiler compiler=mock(StoryboardCompiler.class);StoryboardProjectFiles files=mock(StoryboardProjectFiles.class);CreationRuntimeMapper runs=mock(CreationRuntimeMapper.class);
    FixedProjectJobMapper jobs=mock(FixedProjectJobMapper.class);FixedProjectJobFiles fixedFiles=mock(FixedProjectJobFiles.class);FixedProjectJobPersistenceService fixedJobs=mock(FixedProjectJobPersistenceService.class);MediaUploadMapper media=mock(MediaUploadMapper.class);
    Run run=mock(Run.class);Approval approval=mock(Approval.class);ObjectMapper json=new ObjectMapper();StoryboardProductionBridge bridge;
    final FixedProjectRenderService.Request request=new FixedProjectRenderService.Request(Path.of("/work/run-1/attempt-1/input.json"),"run-1","attempt-1","board-1","a".repeat(64));
    final FixedProjectJob job=new FixedProjectJob("run-1","task-1","default","attempt-1","{}");
    @BeforeEach void setup() throws Exception {
        bridge=new StoryboardProductionBridge(boards,persistence,codec,compiler,files,runs,jobs,fixedFiles,fixedJobs,media);
        var board=mock(Board.class);var brief=mock(Brief.class);var doc=json.createObjectNode().set("candidate",json.createObjectNode());var briefDoc=json.createObjectNode();var candidate=new Candidate(List.of());
        when(media.countTaskByOwner("task-1","default")).thenReturn(1);when(boards.board("task-1","default","board-1")).thenReturn(Optional.of(board));
        when(board.briefId()).thenReturn("brief-1");when(board.documentJson()).thenReturn("board-json");when(brief.documentJson()).thenReturn("brief-json");
        when(persistence.brief("task-1","default","brief-1")).thenReturn(brief);when(persistence.frozen(board)).thenReturn(List.of());
        when(codec.read("board-json")).thenReturn(doc);when(codec.read("brief-json")).thenReturn(briefDoc);when(codec.candidate(doc.path("candidate"))).thenReturn(candidate);
        when(compiler.check("board-1",briefDoc,candidate,List.of())).thenReturn(new Check(true,List.of(),1000,"b".repeat(64)));
        when(compiler.compile(eq("board-1"),eq(briefDoc),eq(candidate),eq(List.of()),eq("run-1"),anyString())).thenReturn(json.createObjectNode());
        when(runs.run("task-1","default","run-1")).thenReturn(Optional.of(run));when(run.kind()).thenReturn("FULL");when(run.status()).thenReturn("QUEUED");
        when(run.requestJson()).thenReturn("run-json");when(codec.read("run-json")).thenReturn(json.createObjectNode().put("storyboardId","board-1").put("renderContentHash","b".repeat(64)));
        when(run.approvalId()).thenReturn("approval-1");when(run.inputFingerprint()).thenReturn("fingerprint");when(approval.inputFingerprint()).thenReturn("fingerprint");
        when(approval.status()).thenReturn("ACTIVE");when(approval.approvalKind()).thenReturn("PRODUCTION");when(runs.approval("task-1","default","approval-1")).thenReturn(Optional.of(approval));
        when(runs.workspace("task-1","default")).thenReturn(Optional.of(new Workspace("task-1","default",4,"run-1",java.math.BigDecimal.ONE,null,null)));
        when(jobs.find("task-1","default","run-1")).thenReturn(Optional.empty());when(files.materialize(any(),eq(List.of()),any())).thenReturn(request);
        when(fixedFiles.freeze("task-1","default",request)).thenReturn(job);
    }
    @Test void preparationApprovalCannotBecomeFullProduction(){when(run.kind()).thenReturn("PREPARE");assertThatThrownBy(()->bridge.attachToRun("task-1","default","board-1","run-1",()->false)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);verifyNoInteractions(files);}
    @Test void temporarilyMissingCommitPreservesPossibleLateQueueInput(){when(fixedJobs.attach(job)).thenThrow(new IllegalStateException("提交超时"));assertThatThrownBy(()->bridge.attachToRun("task-1","default","board-1","run-1",()->false)).hasMessage("提交超时");verify(files,never()).discard(any());}
    @Test void otherRegisteredAttemptMakesThisSourceSafeToDiscard(){var other=new FixedProjectJob("run-1","task-1","default","other-attempt","{}");when(fixedJobs.attach(job)).thenAnswer(call->{when(jobs.find("task-1","default","run-1")).thenReturn(Optional.of(other));throw new IllegalStateException("已有另一个工程");});assertThatThrownBy(()->bridge.attachToRun("task-1","default","board-1","run-1",()->false)).hasMessage("已有另一个工程");verify(files).discard(request);}
    @Test void committedButLostResponsePreservesRegisteredSource(){when(fixedJobs.attach(job)).thenAnswer(call->{when(jobs.find("task-1","default","run-1")).thenReturn(Optional.of(job));throw new IllegalStateException("提交响应丢失");});assertThat(bridge.attachToRun("task-1","default","board-1","run-1",()->false)).isEqualTo(job);verify(files,never()).discard(any());}
    @Test void uncertainCommitDoesNotDeletePossibleQueueInput(){when(fixedJobs.attach(job)).thenAnswer(call->{when(jobs.find("task-1","default","run-1")).thenThrow(new IllegalStateException("读取不可用"));throw new IllegalStateException("提交状态不明");});assertThatThrownBy(()->bridge.attachToRun("task-1","default","board-1","run-1",()->false)).hasMessage("提交状态不明");verify(files,never()).discard(any());}
}
