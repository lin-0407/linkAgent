package com.link.linkagent.creator.creation.storyboard.service;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.link.linkagent.creator.creation.storyboard.model.StoryboardModels.*;
import jakarta.validation.Validator;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

@Component
public class StoryboardCodec {
    private final ObjectMapper json; private final Validator validator;
    public StoryboardCodec(ObjectMapper json,Validator validator) {this.json=json;this.validator=validator;}
    public String write(Object value) {try{return json.writeValueAsString(value);}catch(Exception ex){throw conflict("工程无法序列化");}}
    public JsonNode read(String value) {try{return json.reader().with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(value);}catch(Exception ex){throw conflict("工程不是有效 JSON");}}
    public <T> T decode(JsonNode value,Class<T> type) {try{return json.readerFor(type).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(value);}catch(Exception ex){throw conflict("工程字段不完整或包含未支持字段");}}
    public String hash(Object value) {return digest(write(sort(json.valueToTree(value))));}
    private JsonNode sort(JsonNode n) {
        if(n.isObject()){var result=json.createObjectNode();var keys=new TreeSet<String>();n.fieldNames().forEachRemaining(keys::add);keys.forEach(k->result.set(k,sort(n.get(k))));return result;}
        if(n.isArray()){var a=json.createArrayNode();n.forEach(v->a.add(sort(v)));return a;}return n;
    }
    public static String digest(String value) {try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception ex){throw new IllegalStateException(ex);}}
    public void valid(Object value) {if(value==null || !validator.validate(value).isEmpty())throw conflict("故事板字段、数量或文本超出允许范围");}
    public Candidate candidate(JsonNode root) {var result=decode(root,Candidate.class);valid(result);return result;}
    public void policy(Brief brief,Candidate candidate,boolean generated) { policy(brief,candidate,generated,Set.of()); }
    /** 生成的配音或视频素材不在简报选择范围内，只有本运行实际产出并用于对应来源时才可引用。 */
    public void policy(Brief brief,Candidate candidate,boolean generated,Set<String> generatedAssets) {
        valid(candidate);var doc=read(brief.documentJson());Set<String> assets=new HashSet<>();doc.path("assetIds").forEach(n->assets.add(n.asText()));
        Set<String> sources=new HashSet<>(Set.of("brief:user"));read(brief.sourceJson()).path("materials").forEach(n->sources.add(n.path("sourceId").asText()));
        read(brief.sourceJson()).path("revisions").forEach(n->sources.add(n.path("sourceId").asText()));
        Set<String> ids=new HashSet<>();String mode=doc.path("dialogueMode").asText();
        for(var s:candidate.scenes()) {
            if(!ids.add(s.id()))throw conflict("场景身份重复");
            boolean voiceGenerated=s.narrationAssetId()!=null && generatedAssets.contains(s.narrationAssetId());
            boolean visualGenerated=s.visualAssetId()!=null && generatedAssets.contains(s.visualAssetId());
            if(s.visualAssetId()!=null && !assets.contains(s.visualAssetId()) && !visualGenerated
                    || s.narrationAssetId()!=null && !assets.contains(s.narrationAssetId()) && !voiceGenerated)
                throw conflict("故事板引用了简报范围外素材");
            if(voiceGenerated && !"TTS".equals(s.audioMode()))throw conflict("生成的配音只能用于 TTS 场景，不能替换用户录音或原声");
            if(visualGenerated && !"AI_VIDEO".equals(s.layout()))throw conflict("生成的视频只能用于 AI 视频场景，不能替换用户素材");
            if(!sources.containsAll(s.sourceRefs()) || ("USER_PROVIDED".equals(doc.path("contentKind").asText()) && s.sourceRefs().isEmpty()))throw conflict("事实场景需要引用本简报真实输入来源");
            if(generated && !s.cues().isEmpty())throw conflict("模型不能生成未经音频对齐的字幕时间");
            String required=switch(mode){case "USER_NARRATION"->"NARRATION";case "SOURCE"->"ORIGINAL";case "TTS"->"TTS";default->"NO_DIALOGUE";};
            if(!"MIXED".equals(mode) && !required.equals(s.audioMode()) && !("SOURCE".equals(mode) && "NO_DIALOGUE".equals(s.audioMode())
                    && !Set.of("VIDEO_CLIP","AI_VIDEO").contains(s.layout())))throw conflict("场景声音选择与简报不一致，不能自动替换用户选择");
            if("TTS".equals(s.audioMode()) && s.narration().isBlank())throw conflict("生成配音场景需要真实文稿");
            for(String text:List.of(s.title(),s.narration(),Objects.toString(s.visualPrompt(),""))) if(text.codePoints().anyMatch(c->c<32 && c!='\n'))throw conflict("文字包含不支持的控制字符");
        }
    }
    public static ResponseStatusException conflict(String message){return new ResponseStatusException(HttpStatus.CONFLICT,message);}
}
