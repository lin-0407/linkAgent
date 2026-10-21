package com.link.linkagent.creator.creation.storyboard.service;

import com.link.linkagent.creator.creation.storyboard.mapper.StoryboardMapper;
import com.link.linkagent.creator.creation.storyboard.model.StoryboardModels.*;
import com.link.linkagent.creator.creation.service.CreationRuntimeService;
import com.link.linkagent.creator.creation.asset.service.CreationAssetService;
import com.link.linkagent.creator.media.upload.mapper.MediaUploadMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import java.util.*;
import static com.link.linkagent.creator.creation.storyboard.service.StoryboardCodec.conflict;

@Service
@ConditionalOnProperty(prefix="creator.media",name="enabled",havingValue="true")
public class StoryboardService {
    private final StoryboardMapper mapper;private final StoryboardPersistence persistence;private final StoryboardCompiler compiler;
    private final StoryboardCodec codec;private final MediaUploadMapper media;private final CreationRuntimeService runtime;private final CreationAssetService assets;
    public StoryboardService(StoryboardMapper mapper,StoryboardPersistence persistence,StoryboardCompiler compiler,StoryboardCodec codec,MediaUploadMapper media,CreationRuntimeService runtime,CreationAssetService assets){this.mapper=mapper;this.persistence=persistence;this.compiler=compiler;this.codec=codec;this.media=media;this.runtime=runtime;this.assets=assets;}
    public BriefView saveBrief(String task,String owner,String key,BriefInput input){return briefView(persistence.saveBrief(task,owner,key,input));}
    public List<BriefView> briefs(String task,String owner,int offset,int limit){requireTask(task,owner);return mapper.briefs(task,owner,offset,limit).stream().map(this::briefView).toList();}
    public BriefView brief(String task,String owner,String id){requireTask(task,owner);return briefView(persistence.brief(task,owner,id));}
    public BoardView save(String task,String owner,String key,Save input){return view(persistence.save(task,owner,key,input));}
    public List<BoardView> boards(String task,String owner,int offset,int limit){requireTask(task,owner);return mapper.boards(task,owner,offset,limit).stream().map(this::view).toList();}
    public BoardView board(String task,String owner,String id){requireTask(task,owner);return view(mapper.board(task,owner,id).orElseThrow(()->conflict("故事板不存在")));}
    public Map<String,Object> generate(String task,String owner,String key,Generate input){var job=persistence.generate(task,owner,key,input);return preparation(task,owner,job.runId());}
    public Map<String,Object> estimate(String task,String owner,EstimateInput input){return persistence.estimate(task,owner,input);}
    public Map<String,Object> preparation(String task,String owner,String run){requireTask(task,owner);var job=mapper.job(task,owner,run).orElseThrow(()->conflict("故事板准备运行不存在"));var result=new LinkedHashMap<String,Object>();result.put("run",runtime.getRun(task,owner,run));result.put("storyboardId",job.storyboardId());result.put("fullProductionAvailable",false);return result;}
    public List<StoryboardMapper.Reference> references(String task,String owner,String assetId,int offset,int limit){assets.require(task,owner,assetId);return mapper.references(task,owner,assetId,offset,limit);}
    private BoardView view(Board board){var brief=persistence.brief(board.taskId(),board.ownerId(),board.briefId());var document=codec.read(board.documentJson());var candidate=codec.candidate(document.path("candidate"));return new BoardView(board.storyboardId(),board.revision(),board.briefId(),board.parentStoryboardId(),board.runId(),board.documentHash(),document,compiler.check(board.storyboardId(),codec.read(brief.documentJson()),candidate,persistence.frozen(board)));}
    private BriefView briefView(Brief b){return new BriefView(b.briefId(),b.revision(),codec.read(b.documentJson()),codec.read(b.sourceJson()));}
    private void requireTask(String task,String owner){if(media.countTaskByOwner(task,owner)!=1)throw conflict("创作任务不存在");}
}
