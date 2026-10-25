package com.link.linkagent.creator.creation.revision;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.link.linkagent.creator.creation.revision.RevisionModels.*;
import com.link.linkagent.creator.creation.asset.service.CreationAssetService;
import com.link.linkagent.creator.creation.asset.model.CreationAsset;
import com.link.linkagent.creator.creation.execution.ExecutionMapper;
import com.link.linkagent.creator.creation.storyboard.mapper.StoryboardMapper;
import com.link.linkagent.creator.creation.storyboard.model.StoryboardModels.*;
import com.link.linkagent.creator.creation.storyboard.service.*;
import com.link.linkagent.creator.creation.service.CreationRuntimeService;
import com.link.linkagent.creator.creation.billing.BillingExchangeRate;
import com.link.linkagent.creator.creation.voice.service.CreationVoiceProperties;
import com.link.linkagent.creator.creation.aivideo.service.CreationVideoProperties;
import com.link.linkagent.creator.media.config.CreatorMediaProperties;
import com.link.linkagent.creator.media.upload.mapper.MediaUploadMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.*;
import java.util.*;
import static com.link.linkagent.creator.creation.storyboard.service.StoryboardCodec.conflict;

@Service @Transactional
@ConditionalOnProperty(prefix="creator.media",name="enabled",havingValue="true")
public class RevisionService {
    private final RevisionMapper proposals;
    private final StoryboardMapper boards;
    private final StoryboardPersistence persistence;
    private final StoryboardService storyboards;
    private final StoryboardCodec codec;
    private final StoryboardCompiler compiler;
    private final CreationAssetService assets;
    private final MediaUploadMapper media;
    private final CreationRuntimeService runtime;
    private final ExecutionMapper executions;
    private final RevisionInterpreter interpreter;
    private final ObjectMapper json;
    private final CreationVoiceProperties voice;
    private final CreationVideoProperties video;
    private final CreatorMediaProperties properties;
    private final BillingExchangeRate rates;
    public RevisionService(RevisionMapper proposals,StoryboardMapper boards,StoryboardPersistence persistence,StoryboardService storyboards,
            StoryboardCodec codec,StoryboardCompiler compiler,CreationAssetService assets,MediaUploadMapper media,CreationRuntimeService runtime,
            ExecutionMapper executions,RevisionInterpreter interpreter,ObjectMapper json,CreationVoiceProperties voice,
            CreationVideoProperties video,CreatorMediaProperties properties,BillingExchangeRate rates) {
        this.proposals=proposals;this.boards=boards;this.persistence=persistence;this.storyboards=storyboards;this.codec=codec;
        this.compiler=compiler;this.assets=assets;this.media=media;this.runtime=runtime;this.executions=executions;
        this.interpreter=interpreter;this.json=json;this.voice=voice;this.video=video;this.properties=properties;this.rates=rates;
    }
    public View propose(String task,String owner,String key,Propose input) {
        codec.valid(input);lock(task,owner);
        if(key==null || key.isBlank() || key.length()>128)throw conflict("修改提案需要非空幂等键");
        String hash=codec.hash(input);
        var old=proposals.byKey(task,owner,key);
        if(old.isPresent()){if(!hash.equals(old.get().requestHash()))throw conflict("同键不能提交不同修改");return view(old.get());}
        idle(task,owner,input.expectedWorkspaceRevision());
        var board=boards.board(task,owner,input.storyboardId()).orElseThrow(()->conflict("故事板不存在"));
        var brief=persistence.brief(task,owner,board.briefId());
        if(brief.revision()!=boards.briefRevision(task,owner))throw conflict("简报已变化，请使用当前简报的故事板");
        ObjectNode document=codec.read(brief.documentJson()).deepCopy();
        var source=codec.candidate(codec.read(board.documentJson()).path("candidate"));
        List<Change> changes=input.changes();
        if(input.instruction()!=null && !input.instruction().isBlank()) {
            if(changes!=null)throw conflict("自然语言与结构化修改请选择一种，避免范围混淆");
            var library=new ArrayList<CreationAsset>();
            for(int offset=0;;offset+=100){var page=assets.list(task,owner,offset,100);for(var item:page)library.add(assets.require(task,owner,item.assetId()));if(page.size()<100)break;}
            var interpreted=interpreter.interpret(input.instruction(),source.scenes(),library,document.path("subtitleFontSize").asInt(36),document);
            if(!interpreted.clarifications().isEmpty())return new View(null,board.storyboardId(),input.expectedWorkspaceRevision(),List.of(),null,interpreted.clarifications(),null);
            changes=interpreted.changes();
        }
        if(changes==null || changes.isEmpty() || changes.size()>32)throw conflict("请提供一至三十二项明确修改");
        String id=UUID.randomUUID().toString();
        var nodes=new ArrayList<ObjectNode>();source.scenes().forEach(s->nodes.add(json.valueToTree(s)));
        var frozen=new ArrayList<>(persistence.frozen(board));var differences=new ArrayList<Difference>();
        for(var change:changes){codec.valid(change);applyChange(task,owner,change,nodes,document,frozen,differences);}
        if(differences.isEmpty())throw conflict("修改内容与原版一致，没有需要保存的变化");
        long frames=nodes.stream().mapToLong(n->Math.round(n.path("durationMs").asLong()*30.0/1000)).sum();
        long target=Math.round(frames*1000.0/30);
        if(target<1000 || target>180000)throw conflict("修改后总时长须在 1 至 180 秒之间");
        if(document.path("targetDurationMs").asLong()!=target) {
            differences.add(new Difference(null,"targetDurationMs",document.path("targetDurationMs"),json.getNodeFactory().numberNode(target)));
            document.put("targetDurationMs",target);
            for(String field:List.of("backgroundMusic","ambience"))if(document.path(field).isObject()) {
                var bed=(ObjectNode)document.path(field);long out=bed.path("sourceInMs").asLong()+target;
                if(bed.path("sourceOutMs").asLong()!=out)differences.add(new Difference(null,field+".sourceOutMs",bed.path("sourceOutMs"),json.getNodeFactory().numberNode(out)));
                bed.put("sourceOutMs",out);
            }
        }
        ObjectNode sources=codec.read(brief.sourceJson()).deepCopy();
        ArrayNode revisions=sources.has("revisions")?(ArrayNode)sources.path("revisions"):sources.putArray("revisions");
        revisions.addObject().put("sourceId","revision:"+id).set("changes",json.valueToTree(changes));
        if(codec.write(sources).length()>48000)throw conflict("修改来源记录过长，请新建任务继续创作");
        Set<String> touched=new HashSet<>();for(var diff:differences)if(diff.sceneId()!=null)touched.add(diff.sceneId());
        for(var node:nodes)if(touched.contains(node.path("id").asText()))((ArrayNode)node.path("sourceRefs")).add("revision:"+id);
        var candidate=new Candidate(nodes.stream().map(n->codec.decode(n,Scene.class)).toList());
        var assetIds=new LinkedHashSet<String>();document.path("assetIds").forEach(n->assetIds.add(n.asText()));
        for(var asset:frozen)if(asset.metadata()==null || asset.metadata().path("generated").isMissingNode())assetIds.add(asset.assetId());
        document.set("assetIds",json.valueToTree(assetIds));
        var validation=document.deepCopy();validation.put("expectedWorkspaceRevision",input.expectedWorkspaceRevision());
        codec.valid(codec.decode(validation,BriefInput.class));
        var generatedIds=new HashSet<String>();for(var asset:frozen)if(asset.metadata()!=null && !asset.metadata().path("generated").path("kind").asText("").isBlank())generatedIds.add(asset.assetId());
        codec.policy(new Brief("revision-preview",task,owner,brief.revision()+1,"preview",hash,codec.write(document),codec.write(sources),null),candidate,false,generatedIds);
        var check=compiler.check("revision-preview",document,candidate,frozen);
        var plan=plan(candidate,frozen,check,differences);
        var payload=json.createObjectNode();payload.set("brief",document);payload.set("candidate",json.valueToTree(candidate));payload.set("assets",json.valueToTree(frozen));payload.set("sources",sources);
        payload.set("differences",json.valueToTree(differences));payload.set("plan",json.valueToTree(plan));payload.put("timingSource",codec.read(board.documentJson()).path("subtitleTimingSource").asText("USER_PROVIDED"));
        var proposal=new Proposal(id,task,owner,board.storyboardId(),input.expectedWorkspaceRevision(),key,hash,codec.write(payload),null);
        proposals.insert(proposal);return view(proposal);
    }
    public BoardView apply(String task,String owner,String id,Apply input) {
        codec.valid(input);lock(task,owner);
        var proposal=proposals.find(task,owner,id).orElseThrow(()->conflict("修改提案不存在"));
        if(proposal.appliedStoryboardId()!=null)return storyboards.board(task,owner,proposal.appliedStoryboardId());
        if(proposal.expectedWorkspaceRevision()!=input.expectedWorkspaceRevision())throw conflict("提案依据已变化，请重新预览修改");
        idle(task,owner,input.expectedWorkspaceRevision());
        var payload=codec.read(proposal.documentJson());
        var frozen=new ArrayList<Asset>();payload.path("assets").forEach(n->frozen.add(codec.decode(n,Asset.class)));
        var result=persistence.saveRevision(task,owner,"revision-"+id,proposal.requestHash(),proposal.storyboardId(),input.expectedWorkspaceRevision(),
                codec.candidate(payload.path("candidate")),(ObjectNode)payload.path("brief"),payload.path("timingSource").asText(),Set.of(),List.copyOf(frozen),codec.write(payload.path("sources")));
        if(proposals.apply(id,result.storyboardId())!=1)throw conflict("提案已被其他操作处理");
        return storyboards.board(task,owner,result.storyboardId());
    }
    private void applyChange(String task,String owner,Change change,List<ObjectNode> nodes,ObjectNode brief,List<Asset> frozen,List<Difference> differences) {
        String operation=change.operation();JsonNode value=change.value();
        if("SET_SUBTITLE_SIZE".equals(operation)) {
            if(change.sceneId()!=null || value==null || !value.isIntegralNumber() || value.asInt()<24 || value.asInt()>60)throw conflict("字幕字号须为 24 至 60 的整数，作用于整片");
            replace(null,brief,"subtitleFontSize",value,differences);return;
        }
        if("SET_BGM".equals(operation)) {
            if(change.sceneId()!=null)throw conflict("背景音乐作用于整片");
            if(value!=null && !value.isNull()) {var bed=codec.decode(value,BackgroundMusic.class);codec.valid(bed);requireAsset(task,owner,bed.assetId(),"MUSIC",frozen);}
            replace(null,brief,"backgroundMusic",value==null?json.nullNode():value,differences);return;
        }
        var scene=nodes.stream().filter(n->n.path("id").asText().equals(change.sceneId())).findFirst().orElseThrow(()->conflict("修改场景不属于当前故事板"));
        switch(operation) {
            case "SET_NARRATION" -> {
                if(!"TTS".equals(scene.path("audioMode").asText()))throw conflict("改配音文稿只适用于 AI 配音场景，请先明确声音来源");
                text(value,1000);if(!value.equals(scene.path("narration"))){replace(change.sceneId(),scene,"narration",value,differences);scene.set("cues",json.createArrayNode());}
            }
            case "SET_VISUAL" -> {
                text(value,64);var asset=assets.require(task,owner,value.asText());
                if(value.equals(scene.path("visualAssetId")))return;
                if(!Set.of("IMAGE","VIDEO_CLIP").contains(asset.getKind()))throw conflict("画面只能选择图片或视频素材");
                requireAsset(task,owner,asset.getAssetId(),asset.getKind(),frozen);
                replace(change.sceneId(),scene,"visualAssetId",value,differences);
                replace(change.sceneId(),scene,"layout",json.getNodeFactory().textNode("IMAGE".equals(asset.getKind())?"IMAGE_CARD":"VIDEO_CLIP"),differences);
                scene.set("body",json.createArrayNode());scene.putNull("visualPrompt");
                if("VIDEO_CLIP".equals(asset.getKind())){scene.put("sourceInMs",0).put("sourceOutMs",quantized(scene.path("durationMs").asLong()));}
                else {scene.putNull("sourceInMs");scene.putNull("sourceOutMs");if("ORIGINAL".equals(scene.path("audioMode").asText()))throw conflict("原声场景换成图片后需明确新的声音来源");}
            }
            case "SET_CLIP_RANGE" -> {
                fields(value,Set.of("startMs","endMs"));
                if(!Set.of("VIDEO_CLIP","AI_VIDEO").contains(scene.path("layout").asText()) || !value.path("startMs").isIntegralNumber() || !value.path("endMs").isIntegralNumber())throw conflict("截取只适用于视频场景，起止须为整数毫秒");
                long from=value.path("startMs").asLong(),to=value.path("endMs").asLong();
                if(from<0 || to<=from || to-from>30000)throw conflict("视频截取须为不超过三十秒的有效区间");
                boolean rangeChanged=!value.path("startMs").equals(scene.path("sourceInMs")) || !value.path("endMs").equals(scene.path("sourceOutMs"));
                replace(change.sceneId(),scene,"sourceInMs",value.path("startMs"),differences);replace(change.sceneId(),scene,"sourceOutMs",value.path("endMs"),differences);
                replace(change.sceneId(),scene,"durationMs",json.getNodeFactory().numberNode(to-from),differences);
                if(rangeChanged && "ORIGINAL".equals(scene.path("audioMode").asText()))scene.set("cues",json.createArrayNode());
            }
            case "SET_AUDIO" -> {
                fields(value,Set.of("mode","assetId"));String mode=value.path("mode").asText();
                if(!Set.of("TTS","NARRATION","ORIGINAL","NO_DIALOGUE").contains(mode))throw conflict("不支持的声音来源");
                if(!"NARRATION".equals(mode) && value.hasNonNull("assetId"))throw conflict("只有自己的录音可以指定录音素材");
                if(mode.equals(scene.path("audioMode").asText()) && (!"NARRATION".equals(mode) || value.path("assetId").equals(scene.path("narrationAssetId"))))return;
                if("NARRATION".equals(mode))requireAsset(task,owner,value.path("assetId").asText(),"NARRATION",frozen);
                if("ORIGINAL".equals(mode) && !"VIDEO_CLIP".equals(scene.path("layout").asText()))throw conflict("只有自有视频可选择保留原声");
                replace(change.sceneId(),scene,"audioMode",value.path("mode"),differences);
                replace(change.sceneId(),scene,"narrationAssetId","NARRATION".equals(mode)?value.path("assetId"):json.nullNode(),differences);
                scene.set("cues",json.createArrayNode());brief.put("dialogueMode","MIXED");
            }
            case "SET_FIT" -> {if(value==null || !Set.of("CONTAIN","COVER").contains(value.asText()))throw conflict("画幅适配只能完整显示或居中裁切");replace(change.sceneId(),scene,"fit",value,differences);}
            case "MOVE_SCENE" -> {
                if(value==null || !value.isIntegralNumber() || value.asInt()<0 || value.asInt()>=nodes.size())throw conflict("场景新位置不在范围内");
                int old=nodes.indexOf(scene),next=value.asInt();if(old!=next){nodes.remove(old);nodes.add(next,scene);differences.add(new Difference(change.sceneId(),"position",json.getNodeFactory().numberNode(old),value));}
            }
            default -> throw conflict("未支持的修改类型");
        }
    }
    private void requireAsset(String task,String owner,String id,String kind,List<Asset> frozen) {
        var row=assets.require(task,owner,id);if(!"READY".equals(row.getStatus()) || !kind.equals(row.getKind()))throw conflict("修改素材尚未准备完成或类型不符");
        if(frozen.stream().noneMatch(a->a.assetId().equals(id)))frozen.addAll(persistence.snapshotAssets(task,owner,List.of(id)));
    }
    private void replace(String scene,ObjectNode target,String field,JsonNode value,List<Difference> differences) {
        var before=target.has(field)?target.path(field).deepCopy():json.nullNode();
        if(!before.equals(value)){differences.add(new Difference(scene,field,before,value.deepCopy()));target.set(field,value.deepCopy());}
    }
    private void text(JsonNode value,int maximum){if(value==null || !value.isTextual() || value.asText().isBlank() || value.asText().length()>maximum)throw conflict("修改文字为空或超过长度限制");}
    private void fields(JsonNode value,Set<String> allowed){if(value==null || !value.isObject())throw conflict("修改值格式不正确");value.fieldNames().forEachRemaining(n->{if(!allowed.contains(n))throw conflict("修改中包含未允许字段");});}
    private Plan plan(Candidate candidate,List<Asset> frozen,Check check,List<Difference> differences) {
        var voiceIds=check.problems().stream().filter(p->Set.of("TTS_AUDIO_MISSING","TTS_TEXT_MISMATCH","TTS_CUES_MISSING").contains(p.code())).map(Problem::sceneId).distinct().toList();
        var videoIds=check.problems().stream().filter(p->Set.of("AI_VIDEO_ASSET_MISSING","AI_VIDEO_PROMPT_MISMATCH").contains(p.code())).map(Problem::sceneId).distinct().toList();
        BigDecimal usd=BigDecimal.ZERO;boolean known=true;
        for(var scene:candidate.scenes()) {
            if(voiceIds.contains(scene.id()))usd=usd.add(voice.getUsdPerMillionCharacters().multiply(BigDecimal.valueOf(scene.narration().codePointCount(0,scene.narration().length()))).divide(new BigDecimal("1000000"),12,RoundingMode.CEILING))
                    .add(properties.getProcessing().getAsrUsdPerSecond().multiply(BigDecimal.valueOf((scene.durationMs()+999)/1000)));
            if(videoIds.contains(scene.id())){var duration=video.durations().stream().filter(s->s*1000L>=scene.durationMs()).min(Integer::compareTo);if(duration.isEmpty())known=false;else usd=usd.add(video.getUsdPerSecond().multiply(BigDecimal.valueOf(duration.get())));}
        }
        var reused=new LinkedHashSet<String>();for(var scene:candidate.scenes()){
            if(scene.visualAssetId()!=null && !videoIds.contains(scene.id()))reused.add(scene.visualAssetId());
            if(scene.narrationAssetId()!=null && !voiceIds.contains(scene.id()))reused.add(scene.narrationAssetId());
        }
        var notes=new ArrayList<String>(List.of("采纳提案只保存新版本，不发起付费制作；制作前会重新估算并确认范围。","未改动且仍适用的配音、图片和 AI 片段继续复用。"));
        check.problems().stream().map(Problem::message).distinct().forEach(notes::add);
        return new Plan(voiceIds,videoIds,List.copyOf(reused),differences.stream().anyMatch(d->Set.of("position","durationMs").contains(d.field())),
                known?usd.multiply(rates.current().usdToCny()).setScale(6,RoundingMode.CEILING):null,
                List.copyOf(notes));
    }
    private View view(Proposal proposal) {
        var data=codec.read(proposal.documentJson());var differences=new ArrayList<Difference>();data.path("differences").forEach(n->differences.add(codec.decode(n,Difference.class)));
        return new View(proposal.proposalId(),proposal.storyboardId(),proposal.expectedWorkspaceRevision(),List.copyOf(differences),codec.decode(data.path("plan"),Plan.class),List.of(),proposal.appliedStoryboardId());
    }
    private static long quantized(long ms){return Math.round(Math.round(ms*30.0/1000)*1000.0/30);}
    private void lock(String task,String owner){if(media.lockMediaTask(task,owner).isEmpty())throw conflict("创作任务不存在");media.lockTaskDraftVersions(task,owner);}
    private void idle(String task,String owner,long revision){var snapshot=runtime.snapshot(task,owner);if(snapshot.workspaceRevision()!=revision || snapshot.activeRun()!=null || executions.list(task,owner).stream().anyMatch(e->!Set.of("COMPLETED","CANCELLED").contains(e.status())))throw conflict("任务已变化或仍在制作，请先结束当前制作并重新预览");}
}
