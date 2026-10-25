package com.link.linkagent.creator.creation.execution;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.link.linkagent.common.AesGcmUtil;
import com.link.linkagent.creator.creation.aivideo.service.CreationVideoProperties;
import com.link.linkagent.creator.creation.voice.service.CreationVoiceProperties;
import com.link.linkagent.creator.creation.storyboard.service.StoryboardCodec;
import com.link.linkagent.creator.media.config.CreatorMediaProperties;
import jakarta.annotation.PostConstruct;
import jakarta.validation.constraints.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.net.URI;
import java.util.*;
import static com.link.linkagent.creator.creation.storyboard.service.StoryboardCodec.conflict;

/** 参数保存后重启生效，现有客户端不热换密钥；密钥复用项目已有 AES-GCM，仅存密文。 */
@Service
public class ProviderConfiguration {
    public static final Object START_LOCK=new Object();
    private final ProviderConfigurationMapper mapper;
    private final CreationVideoProperties video;
    private final CreationVoiceProperties voice;
    private final CreatorMediaProperties media;
    private final ObjectMapper json;
    private final String aesKey;
    private final TransactionTemplate transactions;
    private long loadedRevision;
    private volatile boolean restartRequired;
    private String loadError;
    public ProviderConfiguration(ProviderConfigurationMapper mapper,CreationVideoProperties video,
            CreationVoiceProperties voice,CreatorMediaProperties media,ObjectMapper json,
            @Value("${linkagent.secret.aes-key:}")String aesKey,PlatformTransactionManager transactionManager) {
        this.mapper=mapper;this.video=video;this.voice=voice;this.media=media;this.json=json;this.aesKey=aesKey;
        this.transactions=new TransactionTemplate(transactionManager);
    }
    // 密钥在服务内检查长度，避免参数校验异常把被拒绝的密钥原文写入日志。
    public record Save(@NotNull @Min(0)Long expectedRevision,
            @NotBlank @Pattern(regexp="[A-Za-z0-9._-]{1,80}")String videoProvider,
            @NotNull @Size(max=512)String videoBaseUrl,@NotNull @Size(max=128)String videoModel,
            String videoApiKey,@NotNull @Size(min=1,max=12)List<@NotNull @Min(1) @Max(60)Integer> videoDurationsSeconds,
            @NotNull @DecimalMin("0.00000001") @Digits(integer=8,fraction=8)BigDecimal videoUsdPerSecond,
            @NotBlank @Size(max=128)String voiceModel,@NotBlank @Size(max=128)String voiceName,
            @NotNull @DecimalMin("0.00000001") @Digits(integer=8,fraction=8)BigDecimal voiceUsdPerMillionCharacters,
            String dashScopeApiKey) {
        @Override public String toString(){return "ProviderConfiguration.Save(密钥已隐藏)";}
    }
    public record View(long revision,boolean restartRequired,boolean encryptionAvailable,boolean videoKeyConfigured,
            boolean dashScopeKeyConfigured,String loadError,String videoProvider,String videoBaseUrl,String videoModel,
            List<Integer> videoDurationsSeconds,BigDecimal videoUsdPerSecond,String voiceModel,String voiceName,BigDecimal voiceUsdPerMillionCharacters) { }
    @PostConstruct
    public void load() {
        try {
            var saved=mapper.saved();
            if(saved.isEmpty())return;
            JsonNode node=json.readTree(saved.get());
            video.setProvider(node.path("videoProvider").asText(video.getProvider()));video.setBaseUrl(node.path("videoBaseUrl").asText(video.getBaseUrl()));
            video.setModel(node.path("videoModel").asText(video.getModel()));
            if(node.path("videoDurationsSeconds").isArray())video.setSupportedDurationsSeconds(json.convertValue(node.path("videoDurationsSeconds"),new com.fasterxml.jackson.core.type.TypeReference<List<Integer>>(){}));
            if(node.path("videoUsdPerSecond").isNumber())video.setUsdPerSecond(node.path("videoUsdPerSecond").decimalValue());
            voice.setModel(node.path("voiceModel").asText(voice.getModel()));voice.setVoice(node.path("voiceName").asText(voice.getVoice()));
            if(node.path("voiceUsdPerMillionCharacters").isNumber())voice.setUsdPerMillionCharacters(node.path("voiceUsdPerMillionCharacters").decimalValue());
            if(node.hasNonNull("videoKeyEnc"))video.setApiKey(AesGcmUtil.decrypt(node.path("videoKeyEnc").asText(),aesKey));
            if(node.hasNonNull("dashScopeKeyEnc"))media.getPreflight().setDashScopeApiKey(AesGcmUtil.decrypt(node.path("dashScopeKeyEnc").asText(),aesKey));
            loadedRevision=node.path("revision").asLong();
        } catch(Exception error){loadError="制作配置加载失败，请核对已有密钥加密配置并重启后端";restartRequired=true;}
    }
    public View status() {
        synchronized(START_LOCK) {
            var saved=mapper.saved();JsonNode node=defaults();
            try {if(saved.isPresent())node=json.readTree(saved.get());}
            catch(Exception error){throw conflict("已保存的制作配置无法读取，请核对部署配置");}
            long revision=node.path("revision").asLong();restartRequired=loadError!=null || revision!=loadedRevision;
            var durations=new ArrayList<Integer>();node.path("videoDurationsSeconds").forEach(n->durations.add(n.asInt()));
            return new View(revision,restartRequired,aesKey!=null && !aesKey.isBlank(),
                    node.hasNonNull("videoKeyEnc") || notBlank(video.getApiKey()),node.hasNonNull("dashScopeKeyEnc") || notBlank(media.getPreflight().getDashScopeApiKey()),
                    loadError,node.path("videoProvider").asText(),node.path("videoBaseUrl").asText(),node.path("videoModel").asText(),
                    List.copyOf(durations),node.path("videoUsdPerSecond").decimalValue(),node.path("voiceModel").asText(),node.path("voiceName").asText(),node.path("voiceUsdPerMillionCharacters").decimalValue());
        }
    }
    public View save(Save input) {
        synchronized(START_LOCK) {
            validateKey(input.videoApiKey());validateKey(input.dashScopeApiKey());
            if(!input.videoBaseUrl().isBlank())try {
                URI uri=URI.create(input.videoBaseUrl());
                if(!Set.of("http","https").contains(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null || uri.getRawQuery()!=null || uri.getFragment()!=null)throw new IllegalArgumentException();
            } catch(RuntimeException error){throw conflict("视频服务地址须为 HTTP 或 HTTPS 地址，不能包含密钥、查询参数或片段");}
            transactions.executeWithoutResult(transaction->{
                mapper.lockStarts();
                if(mapper.activeCalls()>0)throw conflict("仍有制作或试映在运行，请先结束后再修改供应商配置");
                ObjectNode old=defaults();
                try {var saved=mapper.lockSaved();if(saved.isPresent())old=(ObjectNode)json.readTree(saved.get());}
                catch(Exception error){throw conflict("已保存的制作配置无法读取");}
                if(old.path("revision").asLong()!=input.expectedRevision())throw conflict("配置已被更新，请刷新后重试");
                ObjectNode next=json.valueToTree(input);next.remove(List.of("expectedRevision","videoApiKey","dashScopeApiKey"));
                next.put("revision",input.expectedRevision()+1);
                copyKey(old,next,"videoKeyEnc",input.videoApiKey());copyKey(old,next,"dashScopeKeyEnc",input.dashScopeApiKey());
                try {mapper.save(json.writeValueAsString(next));}
                catch(Exception error){throw conflict("制作配置未能保存，请刷新核对后重试");}
            });
            restartRequired=true;return status();
        }
    }
    private ObjectNode defaults(){var node=json.createObjectNode().put("revision",loadedRevision).put("videoProvider",video.getProvider()).put("videoBaseUrl",video.getBaseUrl()).put("videoModel",video.getModel())
            .put("videoUsdPerSecond",video.getUsdPerSecond()).put("voiceModel",voice.getModel()).put("voiceName",voice.getVoice()).put("voiceUsdPerMillionCharacters",voice.getUsdPerMillionCharacters());node.set("videoDurationsSeconds",json.valueToTree(video.durations()));return node;}
    private void copyKey(ObjectNode old,ObjectNode next,String field,String value) {
        if(notBlank(value)) {try {next.put(field,AesGcmUtil.encrypt(value.strip(),aesKey));}catch(RuntimeException error){throw conflict("密钥无法加密保存，请先配置项目现有的密钥加密项");}}
        else if(old.hasNonNull(field))next.set(field,old.path(field));
    }
    /** 与受理运行共用事务锁，确保“保存配置”和“开始制作”不能同时越过空闲检查。 */
    public void lockForStart(){
        mapper.lockStarts();
        try {var saved=mapper.lockSaved();if(saved.isPresent() && json.readTree(saved.get()).path("revision").asLong()!=loadedRevision)restartRequired=true;}
        catch(Exception error){throw conflict("制作配置无法读取，请核对部署配置后重试");}
        requireReady();
    }
    public void requireReady(){if(restartRequired)throw conflict(disabledReason());}
    public String disabledReason(){return loadError!=null?loadError:restartRequired?"制作供应商配置已保存，请重启后端后再发起新的制作或试映":null;}
    public String fingerprint(){return StoryboardCodec.digest(defaults().toString());}
    public static String voiceFingerprint(CreationVoiceProperties voice,CreatorMediaProperties media){return StoryboardCodec.digest(voice.getModel()+"\n"+voice.getVoice()+"\n"+media.getPreflight().getAsrModel()+"\n"+media.getPreflight().getDashScopeBaseUrl()+"\n"+Objects.toString(media.getPreflight().getDashScopeApiKey(),""));}
    public static String videoFingerprint(CreationVideoProperties video){return StoryboardCodec.digest(video.getProvider()+"\n"+video.getBaseUrl()+"\n"+video.getModel()+"\n"+Objects.toString(video.getApiKey(),""));}
    private static boolean notBlank(String value){return value!=null && !value.isBlank();}
    private static void validateKey(String value){if(value!=null && (value.length()>512 || value.chars().anyMatch(c->c<32)))throw conflict("密钥长度或格式不正确，请重新输入");}
}
