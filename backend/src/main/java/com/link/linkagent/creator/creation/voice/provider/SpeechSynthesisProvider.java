package com.link.linkagent.creator.creation.voice.provider;

/**
 * 语音合成边界。
 * 只有用户明确选择 TTS 的场景才会调用；实现必须一次请求只发送一次，
 * 供应商返回的音频字节与用量交给上层持久化，不在这里落库。
 */
public interface SpeechSynthesisProvider {

    /** 供应商标识，写入配音事实用于事后核对。 */
    String provider();

    String model();

    String voice();

    /**
     * 合成一段场景文稿。
     * 返回实际音频字节、响应标识和供应商报告的计费字符数；报告缺失时字符数为空，
     * 上层不能把缺失用量当成免费。
     */
    Audio synthesize(String text);

    record Audio(byte[] bytes, String contentType, String requestId, Long billedCharacters) { }
}
