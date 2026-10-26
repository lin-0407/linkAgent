package com.link.linkagent.creator.creation.revision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.link.linkagent.creator.creation.asset.model.CreationAsset;
import com.link.linkagent.creator.creation.revision.RevisionModels.Change;
import com.link.linkagent.creator.creation.storyboard.model.StoryboardModels.Scene;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.regex.*;

/** 只解释能完全匹配的简短指令；不明素材、场景或未识别的句子必须澄清，不能只执行其中一部分。 */
@Component
public class RevisionInterpreter {
    private static final String SCENE="第([0-9一二三四五六七八九十]+)(?:个)?(?:场景|幕|段)";
    private final ObjectMapper json;
    public RevisionInterpreter(ObjectMapper json){this.json=json;}
    public record Result(List<Change> changes,List<String> clarifications) { }
    public Result interpret(String instruction,List<Scene> scenes,List<CreationAsset> assets,int fontSize) {
        return interpret(instruction,scenes,assets,fontSize,json.createObjectNode().put("targetDurationMs",scenes.stream().mapToLong(Scene::durationMs).sum()));
    }
    public Result interpret(String instruction,List<Scene> scenes,List<CreationAsset> assets,int fontSize,JsonNode brief) {
        if(instruction.chars().filter(c->c=='“').count()!=instruction.chars().filter(c->c=='”').count()
                || instruction.chars().filter(c->c=='"').count()%2!=0)
            return new Result(List.of(),List.of("修改文稿的引号未配对，请补齐后再预览"));
        var changes=new ArrayList<Change>();var questions=new ArrayList<String>();
        // 同一句里的音乐操作顺序生效，每条变更保留当时的值，避免后续操作覆盖预览。
        JsonNode music=brief.path("backgroundMusic").deepCopy();
        for(String raw:split(instruction)) {
            String part=raw.strip().replaceFirst("[。！]$","");
            if(part.isEmpty())continue;
            Matcher match;
            if(Pattern.matches("(?:取消|去掉|移除)(?:背景音乐|BGM)",part)) {
                music=json.nullNode();
                changes.add(new Change("SET_BGM",null,json.nullNode()));
            }
            else if((match=Pattern.compile("(?:把)?(?:背景音乐|BGM)(?:换成|改为|改成)(.+)").matcher(part)).matches()) {
                String id=asset(unquote(match.group(1)),assets,Set.of("MUSIC"),questions);
                if(id!=null){var bed=music.isObject()?(ObjectNode)music.deepCopy():json.createObjectNode()
                    .put("sourceInMs",0).put("sourceOutMs",brief.path("targetDurationMs").asLong()).put("gainDb",-18).put("fadeInMs",200).put("fadeOutMs",200).put("ducking",true);
                    bed.put("assetId",id);music=bed;changes.add(new Change("SET_BGM",null,bed.deepCopy()));}
            } else if((match=Pattern.compile("(?:背景音乐|BGM)(?:音量)?(?:改为|设为|改成)(-?[0-9]{1,2}(?:\\.[0-9])?)(?:dB|分贝)",Pattern.CASE_INSENSITIVE).matcher(part)).matches()) {
                if(!music.isObject())questions.add("当前没有背景音乐，请先明确要使用的音乐文件");
                else {var bed=(ObjectNode)music.deepCopy();bed.put("gainDb",new java.math.BigDecimal(match.group(1)));music=bed;changes.add(new Change("SET_BGM",null,bed.deepCopy()));}
            } else if((match=Pattern.compile(SCENE+"(?:的)?(?:声音|音轨)(?:来源)?(?:改为|改成|换成)(原声|保留原声|无对白|静音|AI配音|AI 配音)").matcher(part)).matches()) {
                String id=scene(match.group(1),scenes,questions),value=match.group(2);
                if(id!=null)changes.add(new Change("SET_AUDIO",id,json.createObjectNode().put("mode",Set.of("原声","保留原声").contains(value)?"ORIGINAL":value.contains("AI")?"TTS":"NO_DIALOGUE")));
            } else if((match=Pattern.compile(SCENE+"(?:的)?(?:声音|录音)(?:改为|改成|换成)(?:自己的录音)?(.+)").matcher(part)).matches()) {
                String id=scene(match.group(1),scenes,questions),asset=asset(unquote(match.group(2)),assets,Set.of("NARRATION"),questions);
                if(id!=null && asset!=null)changes.add(new Change("SET_AUDIO",id,json.createObjectNode().put("mode","NARRATION").put("assetId",asset)));
            } else if((match=Pattern.compile(SCENE+"(?:的)?(?:画面适配|画幅)(?:改为|改成|设为)(完整显示|居中裁切|铺满)").matcher(part)).matches()) {
                String id=scene(match.group(1),scenes,questions);if(id!=null)changes.add(new Change("SET_FIT",id,json.getNodeFactory().textNode("完整显示".equals(match.group(2))?"CONTAIN":"COVER")));
            } else if(Pattern.matches("(?:把)?字幕(?:字号)?(?:大一点|加大|放大)",part)) {
                if(fontSize>=60)questions.add("字幕已经达到支持的最大字号 60");
                else changes.add(new Change("SET_SUBTITLE_SIZE",null,json.getNodeFactory().numberNode(Math.min(60,fontSize+6))));
            } else if((match=Pattern.compile("(?:把)?字幕(?:字号)?(?:设为|改为|改成)([0-9]{1,2})").matcher(part)).matches())
                changes.add(new Change("SET_SUBTITLE_SIZE",null,json.getNodeFactory().numberNode(Integer.parseInt(match.group(1)))));
            else if((match=Pattern.compile(SCENE+"(?:的)?(?:旁白|配音文稿)(?:改为|改成|换成)(.+)").matcher(part)).matches()) {
                String id=scene(match.group(1),scenes,questions);
                if(id!=null)changes.add(new Change("SET_NARRATION",id,json.getNodeFactory().textNode(unquote(match.group(2)))));
            } else if((match=Pattern.compile(SCENE+"(?:的)?(?:图片|画面|素材|视频)?(?:换成|替换为|改为)(.+)").matcher(part)).matches()) {
                String id=scene(match.group(1),scenes,questions), asset=asset(unquote(match.group(2)),assets,Set.of("IMAGE","VIDEO_CLIP"),questions);
                if(id!=null && asset!=null)changes.add(new Change("SET_VISUAL",id,json.getNodeFactory().textNode(asset)));
            } else if((match=Pattern.compile(SCENE+"(?:视频)?(?:截取|改为)?(?:从)?([0-9]{1,7}(?:\\.[0-9]{1,3})?)秒(?:到|至|-)([0-9]{1,7}(?:\\.[0-9]{1,3})?)秒").matcher(part)).matches()) {
                String id=scene(match.group(1),scenes,questions);
                if(id!=null)changes.add(new Change("SET_CLIP_RANGE",id,json.createObjectNode()
                        .put("startMs",new java.math.BigDecimal(match.group(2)).movePointRight(3).longValueExact())
                        .put("endMs",new java.math.BigDecimal(match.group(3)).movePointRight(3).longValueExact())));
            } else if((match=Pattern.compile(SCENE+"(?:移到|放到)"+SCENE+"前(?:面)?").matcher(part)).matches()) {
                String from=scene(match.group(1),scenes,questions),to=scene(match.group(2),scenes,questions);
                if(from!=null && to!=null) {
                    int position=scenes.stream().map(Scene::id).toList().indexOf(to);
                    int old=scenes.stream().map(Scene::id).toList().indexOf(from);
                    changes.add(new Change("MOVE_SCENE",from,json.getNodeFactory().numberNode(old<position?position-1:position)));
                }
            } else questions.add("无法完整确定这条修改：“"+part+"”。请明确场景编号和素材完整文件名，或在场景卡中编辑。");
        }
        if(changes.isEmpty() && questions.isEmpty())questions.add("请填写要修改的场景和内容");
        return new Result(questions.isEmpty()?List.copyOf(changes):List.of(),List.copyOf(questions));
    }
    private static String scene(String value,List<Scene> scenes,List<String> questions) {
        int position=number(value)-1;
        if(position<0 || position>=scenes.size()){questions.add("故事板中不存在第 "+(position+1)+" 个场景");return null;}
        return scenes.get(position).id();
    }
    private static int number(String value) {
        try {return Integer.parseInt(value);}catch(NumberFormatException ignored){ }
        if(!value.matches("[一二三四五六七八九]?十[一二三四五六七八九]?|[一二三四五六七八九]"))return -1;
        String digits="零一二三四五六七八九";
        int ten=value.indexOf('十');
        if(ten>=0){int high=ten==0?1:digits.indexOf(value.charAt(0)),low=ten==value.length()-1?0:digits.indexOf(value.charAt(ten+1));return high*10+low;}
        return value.length()==1?digits.indexOf(value.charAt(0)):-1;
    }
    private static String asset(String name,List<CreationAsset> assets,Set<String> kinds,List<String> questions) {
        var matches=assets.stream().filter(a->"READY".equals(a.getStatus()) && kinds.contains(a.getKind())
                && name.equals(a.getFileName())).toList();
        if(matches.size()!=1){questions.add("素材“"+name+"”未准备完成或有同名文件，请在场景卡里明确选择");return null;}
        return matches.getFirst().getAssetId();
    }
    private static String unquote(String value){return value.strip().replaceFirst("^[“\"‘']","").replaceFirst("[”\"’']$","");}
    private static List<String> split(String input) {
        var result=new ArrayList<String>();var current=new StringBuilder();char close=0;
        for(char c:input.toCharArray()) {
            if(close!=0){current.append(c);if(c==close)close=0;}
            else if(c=='“' || c=='‘' || c=='"'){close=c=='“'?'”':c=='‘'?'’':'"';current.append(c);}
            else if(c=='，' || c==',' || c==';' || c=='；' || c=='\n'){result.add(current.toString());current.setLength(0);}
            else current.append(c);
        }
        result.add(current.toString());return result;
    }
}
