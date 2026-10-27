package com.link.linkagent.creator.creation.storyboard.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.link.linkagent.creator.creation.storyboard.mapper.StoryboardMapper;
import com.link.linkagent.creator.creation.storyboard.model.StoryboardAudioModels.*;
import com.link.linkagent.creator.creation.storyboard.model.StoryboardModels.*;
import com.link.linkagent.creator.media.upload.mapper.MediaUploadMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;
import static com.link.linkagent.creator.creation.storyboard.service.StoryboardCodec.conflict;

/** 导入用户已有时间码，不调用语音识别，也不按文稿字数猜测音频时长。 */
@Service
@ConditionalOnProperty(prefix="creator.media",name="enabled",havingValue="true")
public class StoryboardAudioService {
    private static final int FPS=30, MAX_SRT_BYTES=64*1024;
    private static final Pattern TIMING=Pattern.compile("(\\d{2}):([0-5]\\d):([0-5]\\d),(\\d{3}) --> (\\d{2}):([0-5]\\d):([0-5]\\d),(\\d{3})");
    private final StoryboardMapper mapper;
    private final StoryboardPersistence persistence;
    private final StoryboardCodec codec;
    private final StoryboardCompiler compiler;
    private final MediaUploadMapper media;
    private final ObjectMapper json;

    public StoryboardAudioService(StoryboardMapper mapper,StoryboardPersistence persistence,StoryboardCodec codec,
            StoryboardCompiler compiler,MediaUploadMapper media,ObjectMapper json) {
        this.mapper=mapper;this.persistence=persistence;this.codec=codec;this.compiler=compiler;this.media=media;this.json=json;
    }

    public Board importSubtitles(String task,String owner,String key,String boardId,SubtitleImport input) {
        codec.valid(input);requireTask(task,owner);
        if(key==null || key.isBlank() || key.length()>128)throw conflict("字幕导入需要非空幂等键，最多 128 字符");
        String scopedKey="subtitles-"+StoryboardCodec.digest(key);
        String requestHash=codec.hash(Map.of("storyboardId",boardId,"input",input));
        // 保留原始 SRT 请求摘要，避免格式规范化把不同请求错误当作同一次导入。
        var replay=mapper.boardByKey(task,owner,scopedKey);
        if(replay.isPresent()) {
            if(!requestHash.equals(replay.get().requestHash()))throw conflict("同一字幕导入幂等键对应不同请求");
            return replay.get();
        }
        var board=board(task,owner,boardId);var brief=persistence.brief(task,owner,board.briefId());
        if(mapper.briefRevision(task,owner)!=brief.revision())throw conflict("简报已更新，请使用当前简报的故事板导入字幕");
        Candidate original=candidate(board);List<Asset> frozen=persistence.frozen(board);
        var selected=original.scenes().stream().filter(s->s.id().equals(input.sceneId())).findFirst()
                .orElseThrow(()->conflict("字幕目标场景不存在"));
        if(!Set.of("NARRATION","ORIGINAL").contains(selected.audioMode()))throw conflict("只有实际录音或视频原声场景可以导入相对音轨字幕");
        List<Cue> cues=parse(input.srt());List<Scene> scenes=new ArrayList<>(original.scenes());
        scenes.set(scenes.indexOf(selected),replaceCues(selected,cues));Candidate changed=new Candidate(List.copyOf(scenes));
        var check=compiler.check(boardId,codec.read(brief.documentJson()),changed,frozen);
        // 只要求本场景及全片字幕容量有效，其他场景待准备不应阻止逐场景补齐字幕。
        var problem=check.problems().stream().filter(p->selected.id().equals(p.sceneId()) || "CUES_LIMIT".equals(p.code())).findFirst();
        if(problem.isPresent())throw conflict("字幕无法绑定当前真实音轨："+problem.get().message());
        return persistence.saveWithRequestHash(task,owner,scopedKey,
                new Save(input.expectedWorkspaceRevision(),board.briefId(),boardId,changed),requestHash);
    }

    /**
     * 采纳时长提案：只接受当前报告中真实存在的 EXTEND_SCENE / SHORTEN_SCENE 及其原值，
     * 同步视频选区、重算全片时间线和音乐区间，并新建简报与故事板版本保留历史。
     */
    public TimelineApplyResult applyTimeline(String task,String owner,String key,String boardId,TimelineApply input) {
        codec.valid(input);
        if(key==null || key.isBlank() || key.length()>128)throw conflict("时间线采纳需要非空幂等键，最多 128 字符");
        String scopedKey="timeline-"+StoryboardCodec.digest(key);
        String requestHash=codec.hash(Map.of("storyboardId",boardId,"input",input));
        var replay=mapper.boardByKey(task,owner,scopedKey);
        if(replay.isPresent()) {
            if(!requestHash.equals(replay.get().requestHash()))throw conflict("同一时间线幂等键对应不同请求");
            return describe(task,owner,replay.get());
        }
        requireTask(task,owner);var board=board(task,owner,boardId);
        var brief=persistence.brief(task,owner,board.briefId());
        if(brief.revision()!=mapper.briefRevision(task,owner))
            throw conflict("当前简报已更新，请使用最新简报的故事板调整时间线");
        Candidate candidate=candidate(board);List<Asset> frozen=persistence.frozen(board);
        Map<String,Asset> assets=new HashMap<>();frozen.forEach(asset->assets.put(asset.assetId(),asset));
        Map<String,TimingProposal> allowed=new HashMap<>();
        for(var scene:timing(task,owner,boardId).scenes())for(var proposal:scene.proposals())
            allowed.put(scene.sceneId()+"|"+proposal.code(),proposal);
        Map<String,TimelineAdjustment> adjustments=new LinkedHashMap<>();
        for(var adjustment:input.adjustments()) {
            if(adjustments.putIfAbsent(adjustment.sceneId(),adjustment)!=null)
                throw conflict("同一场景不能在一次请求中重复调整");
            var proposal=allowed.get(adjustment.sceneId()+"|"+adjustment.proposalCode());
            if(proposal==null)
                throw conflict("场景 "+adjustment.sceneId()+" 当前不存在 "+adjustment.proposalCode()+" 提案，不能凭任意时长调整");
            if(proposal.proposedDurationMs()==null || proposal.proposedDurationMs()!=adjustment.durationMs())
                throw conflict("采纳时长必须与当前时长提案给出的毫秒数一致");
        }
        List<Scene> scenes=new ArrayList<>();
        for(var scene:candidate.scenes()) {
            var adjustment=adjustments.get(scene.id());
            if(adjustment==null){scenes.add(scene);continue;}
            long quantized=Math.round(frames(adjustment.durationMs())*1000.0/FPS);
            Long in=scene.sourceInMs(),out=scene.sourceOutMs();
            if(Set.of("VIDEO_CLIP","AI_VIDEO").contains(scene.layout())) {
                if(in==null || out==null)throw conflict("视频场景缺少原始截取区间，无法随场景时长同步延长");
                out=in+quantized;
                double available=number(stream(assets.get(scene.visualAssetId()),"video").path("duration"),0)*1000;
                if(available<=0)throw conflict("视频素材缺少可靠实际时长，无法确认延长后的截取区间");
                if(out>available+.001)throw conflict("延长后的视频区间超出素材实际时长，请补充视频或缩短文稿后重新生成");
                if(out>1800000)throw conflict("延长后的视频区间超过工程允许的最大截取时间");
            }
            scenes.add(new Scene(scene.id(),scene.layout(),scene.title(),scene.body(),scene.narration(),
                    adjustment.durationMs(),scene.visualAssetId(),in,out,scene.fit(),scene.audioMode(),
                    scene.narrationAssetId(),scene.cues(),scene.sourceRefs(),scene.visualPrompt()));
        }
        long totalFrames=0;for(var scene:scenes)totalFrames+=frames(scene.durationMs());
        if(totalFrames<FPS || totalFrames>FPS*180)throw conflict("调整后的总时长必须在 1 至 180 秒之间");
        long target=Math.round(totalFrames*1000.0/FPS);
        ObjectNode document=(ObjectNode)codec.read(brief.documentJson()).deepCopy();
        document.put("targetDurationMs",target);
        JsonNode music=document.path("backgroundMusic");
        if(music.isObject()) {
            // 用户选择的音乐区间语义是覆盖全片，总时长变化时同步首尾；超出素材实际尾部必须重新选择。
            long start=music.path("sourceInMs").asLong(),end=start+target;
            double available=audioDurationMs(assets.get(music.path("assetId").asText()));
            if(available<=0)throw conflict("背景音乐素材缺少可靠时长，无法随总时长调整");
            if(end>available+.001)
                throw conflict("背景音乐区间需要重新选择：随总时长延展到 "+end+"ms 会超出素材实际时长");
            if(end>1200000)throw conflict("背景音乐区间超过允许的最大 20 分钟");
            if(music.path("fadeInMs").asLong()+music.path("fadeOutMs").asLong()>end-start)
                throw conflict("背景音乐淡入淡出总长超过新的音乐区间");
            ((ObjectNode)music).put("sourceOutMs",end);
        }
        JsonNode ambience=document.path("ambience");
        if(ambience.isObject()) {
            long start=ambience.path("sourceInMs").asLong(),end=start+target;
            double available=audioDurationMs(assets.get(ambience.path("assetId").asText()));
            if(available<=0)throw conflict("环境声素材缺少可靠时长，无法随总时长调整");
            if(end>available+.001)
                throw conflict("环境声区间需要重新选择：随总时长延展到 "+end+"ms 会超出素材实际时长");
            if(end>1200000)throw conflict("环境声区间超过允许的最大 20 分钟");
            if(ambience.path("fadeInMs").asLong()+ambience.path("fadeOutMs").asLong()>end-start)
                throw conflict("环境声淡入淡出总长超过新的环境声区间");
            ((ObjectNode)ambience).put("sourceOutMs",end);
        }
        // 采纳必须不引入新问题；原有缺配音、缺字幕仍保留，由作者按其它提案继续补齐。
        var introduced=introducedProblems(boardId,brief,candidate,frozen,document,scenes);
        if(!introduced.isEmpty())
            throw conflict("采纳后会引入新问题："+introduced.getFirst().message()+"；请先处理该场景或改用其他提案");
        String timingSource=codec.read(board.documentJson()).path("subtitleTimingSource").asText("");
        if(!Set.of("USER_PROVIDED","ASR_ALIGNED","NOT_PROVIDED").contains(timingSource))timingSource="USER_PROVIDED";
        Set<String> generatedVoices=new HashSet<>();
        for(var asset:frozen)if(asset.metadata()!=null && "TTS".equals(asset.metadata().path("generated").path("kind").asText("")))
            generatedVoices.add(asset.assetId());
        var saved=persistence.saveTimeline(task,owner,scopedKey,requestHash,boardId,input.expectedWorkspaceRevision(),
                new Candidate(List.copyOf(scenes)),document,timingSource,Set.copyOf(generatedVoices),frozen);
        return describe(task,owner,saved);
    }

    /** 只拦截本次采纳新引入的问题；原本就缺配音或缺字幕的场景仍允许逐项补齐。 */
    private List<Problem> introducedProblems(String boardId,Brief brief,Candidate before,List<Asset> frozen,
            ObjectNode document,List<Scene> scenes) {
        Set<String> existing=new HashSet<>();
        for(var problem:compiler.check(boardId,codec.read(brief.documentJson()),before,frozen).problems())
            existing.add(Objects.toString(problem.sceneId(),"")+"|"+problem.code());
        List<Problem> introduced=new ArrayList<>();
        for(var problem:compiler.check("timeline-check",document,new Candidate(List.copyOf(scenes)),frozen).problems())
            if(!existing.contains(Objects.toString(problem.sceneId(),"")+"|"+problem.code()))introduced.add(problem);
        return introduced;
    }

    private TimelineApplyResult describe(String task,String owner,Board saved) {
        var brief=persistence.brief(task,owner,saved.briefId());
        long target=codec.read(brief.documentJson()).path("targetDurationMs").asLong();
        List<AppliedAdjustment> applied=new ArrayList<>();List<String> notes=new ArrayList<>();
        if(saved.parentStoryboardId()!=null) {
            var parent=mapper.board(task,owner,saved.parentStoryboardId()).orElse(null);
            if(parent!=null) {
                Map<String,Scene> before=new HashMap<>();
                candidate(parent).scenes().forEach(scene->before.put(scene.id(),scene));
                for(var scene:candidate(saved).scenes()) {
                    var old=before.get(scene.id());
                    if(old!=null && old.durationMs()!=scene.durationMs())
                        applied.add(new AppliedAdjustment(scene.id(),scene.durationMs()>old.durationMs()
                                ?"EXTEND_SCENE":"SHORTEN_SCENE",old.durationMs(),scene.durationMs()));
                }
                JsonNode oldBrief=codec.read(persistence.brief(task,owner,parent.briefId()).documentJson());
                JsonNode newBrief=codec.read(brief.documentJson());
                notes.addAll(rangeNotes("背景音乐",oldBrief.path("backgroundMusic"),newBrief.path("backgroundMusic")));
                notes.addAll(rangeNotes("环境声",oldBrief.path("ambience"),newBrief.path("ambience")));
            }
        }
        return new TimelineApplyResult(view(saved),brief.revision(),target,List.copyOf(applied),List.copyOf(notes));
    }

    private BoardView view(Board board) {
        var brief=persistence.brief(board.taskId(),board.ownerId(),board.briefId());
        var document=codec.read(board.documentJson());var candidate=codec.candidate(document.path("candidate"));
        return new BoardView(board.storyboardId(),board.revision(),board.briefId(),board.parentStoryboardId(),
                board.runId(),board.documentHash(),document,
                compiler.check(board.storyboardId(),codec.read(brief.documentJson()),candidate,persistence.frozen(board)));
    }

    private List<String> rangeNotes(String label,JsonNode before,JsonNode after) {
        if(!before.isObject() || !after.isObject())return List.of();
        long oldEnd=before.path("sourceOutMs").asLong(),newEnd=after.path("sourceOutMs").asLong();
        if(oldEnd==newEnd)return List.of();
        return List.of(label+" 区间终点已随总时长从 "+oldEnd+"ms 调整为 "+newEnd+"ms");
    }

    /** 决策依据来自真实媒体；这里只读已冻结素材的规范化探测结果，不重新解码。 */
    private double audioDurationMs(Asset asset) {
        if(asset==null || !"MUSIC".equals(asset.kind()) || !"audio/wav".equals(asset.contentType()))return 0;
        double track=number(stream(asset,"audio").path("duration"),0);
        double format=asset.metadata()==null?0:number(asset.metadata().path("normalized").path("format").path("duration"),0);
        if(track>0 && format>0)return Math.min(track,format)*1000;
        return Math.max(track,format)*1000;
    }

    private static long frames(long millis) {return Math.round(millis*(double)FPS/1000);}

    public TimingReport timing(String task,String owner,String boardId) {
        requireTask(task,owner);var board=board(task,owner,boardId);var brief=persistence.brief(task,owner,board.briefId());
        var document=codec.read(brief.documentJson());Candidate candidate=candidate(board);
        List<Asset> frozen=persistence.frozen(board);Map<String,Asset> assets=new HashMap<>();frozen.forEach(a->assets.put(a.assetId(),a));
        List<SceneTiming> result=new ArrayList<>();List<TimingProposal> proposals=new ArrayList<>();long totalFrames=0;
        for(var scene:candidate.scenes()) {
            long frames=Math.round(scene.durationMs()*FPS/1000.0);totalFrames+=frames;
            double duration=frames*1000.0/FPS;long sceneMs=Math.round(duration);Long audioMs=null;String source="NOT_REQUIRED";
            List<TimingProposal> own=new ArrayList<>();
            if(Set.of("VIDEO_CLIP","AI_VIDEO").contains(scene.layout())) {
                var visual=assets.get(scene.visualAssetId());double actual=number(stream(visual,"video").path("duration"),0)*1000;
                long begin=scene.sourceInMs()==null?-1:scene.sourceInMs(),end=scene.sourceOutMs()==null?-1:scene.sourceOutMs();
                if(visual==null || !"VIDEO_CLIP".equals(visual.kind()) || actual<=0)
                    own.add(proposal(scene,"SUPPLY_VIDEO_CLIP","请补充本场景具有可靠实际时长的视频素材",null));
                else if(begin<0 || end<=begin || end>actual+.001)
                    own.add(proposal(scene,"SUPPLY_MORE_VIDEO","截取区间超出实际视频，请重新选择区间、缩短场景或补充足够长的素材",null));
                if(begin>=0 && end>begin && Math.abs((end-begin)-duration)>1.000001)
                    own.add(proposal(scene,"ADJUST_CLIP_RANGE","重新选择与场景帧长一致的视频区间，不会循环或变速补齐",sceneMs));
            }
            if("NARRATION".equals(scene.audioMode()) || "TTS".equals(scene.audioMode())) {
                boolean tts="TTS".equals(scene.audioMode());
                var voice=assets.get(scene.narrationAssetId());double actual=narrationDuration(voice);
                if(actual<=0) {
                    source=tts?"NOT_GENERATED":"MISSING";
                    own.add(proposal(scene,tts?"PREPARE_REAL_AUDIO":"SUPPLY_RECORDING",
                            tts?"配音尚未生成；有真实音频后才能给出时长建议":"请提供本场景真实录音，再根据实际时长安排时间线",null));
                } else {
                    source="NORMALIZED_MEDIA";audioMs=Math.round(actual);
                    audioProposals(scene,actual,duration,assets,own,sceneMs,tts);
                }
            } else if("ORIGINAL".equals(scene.audioMode())) {
                var visual=assets.get(scene.visualAssetId());JsonNode track=stream(visual,"audio"),video=stream(visual,"video");
                double begin=scene.sourceInMs()==null?-1:scene.sourceInMs();double end=scene.sourceOutMs()==null?-1:scene.sourceOutMs();
                double trackStart=(number(track.path("start_time"),0)-number(video.path("start_time"),0))*1000;
                double trackEnd=trackStart+number(track.path("duration"),0)*1000;
                double available=track.isMissingNode()?0:Math.max(0,Math.min(end,trackEnd)-Math.max(begin,trackStart));
                if(visual==null || !"VIDEO_CLIP".equals(visual.kind()) || begin<0 || end<=begin || available<=0) {
                    source="MISSING";own.add(proposal(scene,"SUPPLY_SOURCE_AUDIO","所选视频区间没有可靠原声，请重新截取或补充带原声的视频素材",null));
                } else {
                    source="NORMALIZED_MEDIA";audioMs=Math.round(available);
                    if(available<duration-1000.0/FPS)own.add(proposal(scene,"CONFIRM_SOURCE_SILENCE","原声只覆盖部分场景，请确认保留原始静音区间，或重新选择视频区间",null));
                }
            } else if("TTS".equals(scene.audioMode())) {
                source="NOT_GENERATED";own.add(proposal(scene,"PREPARE_REAL_AUDIO","配音尚未生成；有真实音频后才能给出时长建议",null));
            }
            Long difference=audioMs==null?null:audioMs-sceneMs;
            result.add(new SceneTiming(scene.id(),scene.audioMode(),sceneMs,audioMs,difference,source,List.copyOf(own)));
            proposals.addAll(own);
        }
        long total=Math.round(totalFrames*1000.0/FPS),target=document.path("targetDurationMs").asLong();
        if(Math.abs(totalFrames*1000.0/FPS-target)>1000.0/FPS+.000001)
            proposals.add(new TimingProposal(null,"CONFIRM_TOTAL_DURATION","场景总帧长与简报目标不一致，请选择调整场景或创建新简报确认总时长",total));
        // 音乐与对白共用全片时间线；不足时提出问题，不用循环或读取选区外音频补时。
        compiler.check(boardId,document,candidate,frozen).problems().stream()
                .filter(p->p.code().startsWith("BGM_") || p.code().startsWith("AMB_")
                        || "TTS_TEXT_MISMATCH".equals(p.code()) || "TTS_TEXT_MISSING".equals(p.code()))
                .forEach(p->{
                    boolean bed=p.code().startsWith("BGM_") || p.code().startsWith("AMB_");
                    proposals.add(new TimingProposal(bed?null:p.sceneId(),p.code(),
                            bed?p.message()+"；请重新选择区间、补充素材或明确调整全片时长":p.message(),null));
                });
        return new TimingReport(boardId,brief.briefId(),target,total,total-target,List.copyOf(result),List.copyOf(proposals),!proposals.isEmpty());
    }

    /** 真实音频与场景帧长的差异只给提案；配音用重新生成或调整场景解决，不把文字长度当音频时长。 */
    private void audioProposals(Scene scene,double actual,double duration,Map<String,Asset> assets,List<TimingProposal> own,long sceneMs,boolean tts) {
        if(actual>30000.001)own.add(proposal(scene,"SPLIT_OR_RECORD_AGAIN",tts
                ?"配音超过单场景 30 秒，请拆分场景或缩短文稿后重新生成"
                :"录音超过单场景 30 秒，请拆分录音与场景，或重新录制",null));
        if(actual>duration+.001) {
            long extended=(long)Math.ceil(Math.ceil(actual*FPS/1000.0)*1000/FPS);
            if(extended<=30000) {
                if(Set.of("VIDEO_CLIP","AI_VIDEO").contains(scene.layout())) {
                    double available=number(stream(assets.get(scene.visualAssetId()),"video").path("duration"),0)*1000;
                    long begin=scene.sourceInMs()==null?-1:scene.sourceInMs();
                    if(begin>=0 && begin+extended<=available+.001)
                        own.add(proposal(scene,"EXTEND_SCENE","延长场景时须同步延长所选视频区间，并重新确认全片目标时长",extended));
                    else own.add(proposal(scene,"SUPPLY_MORE_VIDEO","完整音频需要更长画面，请补充视频或换用图片后再延长场景；不能循环或定格补齐",extended));
                } else own.add(proposal(scene,"EXTEND_SCENE",tts
                        ?"延长场景并重新确认全片目标时长，完整保留这段配音"
                        :"延长场景并重新确认全片目标时长，完整保留这段录音",extended));
            }
            own.add(proposal(scene,"RECORD_AGAIN",tts
                    ?"保持场景长度，缩短文稿后重新生成配音"
                    :"保持场景长度，重新录制不超过场景时长的录音",sceneMs));
        } else if(duration-actual>1000.0/FPS) {
            long shortened=(long)Math.ceil(Math.ceil(actual*FPS/1000.0)*1000/FPS);
            own.add(proposal(scene,"SHORTEN_SCENE","VIDEO_CLIP".equals(scene.layout())
                    ?"可缩短场景并同步调整视频选区，重新确认总时长；也可明确保留音频后的画面留白"
                    :"可缩短场景并重新确认总时长，也可明确保留音频后的画面留白",shortened));
        }
    }

    private List<Cue> parse(String srt) {
        if(!StandardCharsets.UTF_8.newEncoder().canEncode(srt) || srt.getBytes(StandardCharsets.UTF_8).length>MAX_SRT_BYTES)
            throw conflict("字幕必须是有效 UTF-8 文本且不超过 64 KiB");
        String normalized=srt.startsWith("\uFEFF")?srt.substring(1):srt;
        normalized=normalized.replace("\r\n","\n").replace('\r','\n').strip();
        if(normalized.isEmpty())throw conflict("字幕内容不能为空");
        String[] blocks=normalized.split("\\n[\\t ]*\\n+");
        if(blocks.length>100)throw conflict("单场景最多导入 100 条字幕");
        List<Cue> cues=new ArrayList<>();Set<Integer> indexes=new HashSet<>();long previousEnd=0;
        for(String block:blocks) {
            String[] lines=block.split("\n",-1);
            if(lines.length<3 || !lines[0].strip().matches("[0-9]{1,9}"))throw conflict("SRT 每条字幕必须包含编号、时间范围和正文");
            int index=Integer.parseInt(lines[0].strip());
            if(index<=0 || !indexes.add(index))throw conflict("SRT 字幕编号须为不重复的正整数");
            var match=TIMING.matcher(lines[1].strip());if(!match.matches())throw conflict("SRT 时间必须采用 hh:mm:ss,mmm --> hh:mm:ss,mmm 格式");
            long start=millis(match,1),end=millis(match,5);
            String text=String.join("\n",Arrays.copyOfRange(lines,2,lines.length)).strip();
            if(start<previousEnd || end<=start)throw conflict("SRT 时间必须按序、不得重叠且结束晚于开始");
            if(text.isBlank() || text.length()>120 || text.codePoints().anyMatch(c->c<32 && c!='\n'))throw conflict("每条字幕须为 1 至 120 字符，不能包含控制字符");
            cues.add(new Cue(start,end,text));previousEnd=end;
        }
        return List.copyOf(cues);
    }
    private static long millis(java.util.regex.Matcher m,int offset) {
        return Long.parseLong(m.group(offset))*3600000+Long.parseLong(m.group(offset+1))*60000
                +Long.parseLong(m.group(offset+2))*1000+Long.parseLong(m.group(offset+3));
    }
    private Scene replaceCues(Scene s,List<Cue> cues) {
        return new Scene(s.id(),s.layout(),s.title(),s.body(),s.narration(),s.durationMs(),s.visualAssetId(),s.sourceInMs(),s.sourceOutMs(),s.fit(),s.audioMode(),s.narrationAssetId(),cues,s.sourceRefs(),s.visualPrompt());
    }
    private Candidate candidate(Board board) {return codec.candidate(codec.read(board.documentJson()).path("candidate"));}
    private Board board(String task,String owner,String id) {return mapper.board(task,owner,id).orElseThrow(()->conflict("故事板不存在或不属于本任务"));}
    private void requireTask(String task,String owner) {if(media.countTaskByOwner(task,owner)!=1)throw conflict("创作任务不存在");}
    private TimingProposal proposal(Scene scene,String code,String message,Long duration) {return new TimingProposal(scene.id(),code,message,duration);}
    private double narrationDuration(Asset asset) {
        if(asset==null || !"NARRATION".equals(asset.kind()) || !"audio/wav".equals(asset.contentType()))return 0;
        JsonNode track=stream(asset,"audio");
        if(!"pcm_s16le".equals(track.path("codec_name").asText()) || track.path("channels").asInt()<1
                || track.path("channels").asInt()>2 || track.path("sample_rate").asInt()<8000 || track.path("sample_rate").asInt()>48000)return 0;
        return Math.max(number(track.path("duration"),0),asset.metadata()==null?0:number(asset.metadata().path("normalized").path("format").path("duration"),0))*1000;
    }
    private JsonNode stream(Asset asset,String kind) {
        if(asset!=null && asset.metadata()!=null)for(JsonNode value:asset.metadata().path("normalized").path("streams"))
            if(kind.equals(value.path("codec_type").asText()) && value.path("disposition").path("attached_pic").asInt()!=1)return value;
        return json.missingNode();
    }
    private static double number(JsonNode value,double fallback) {
        try {double number=Double.parseDouble(value.asText());return Double.isFinite(number)?number:fallback;}
        catch(RuntimeException ignored){return fallback;}
    }
}
