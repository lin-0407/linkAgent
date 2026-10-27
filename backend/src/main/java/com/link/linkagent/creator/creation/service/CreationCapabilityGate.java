package com.link.linkagent.creator.creation.service;

/**
 * 制作能力的运行期闸门。
 * <p>
 * 部署配置只提供默认值，设置面板写入的覆盖值在每次受理和领取运行时重新读取，
 * 因此开关会在下一次请求或下一轮扫描生效，不需要重启服务。
 * 媒体对象存储、上传与渲染 Bean 仍是启动期装配，这里只控制依赖媒体的付费分析与生成能力。
 */
public interface CreationCapabilityGate {
    default String disabledReason(){return null;}
    default void requireStartReady(){ }

    /** 媒体基础设施是否在启动时装配；未装配时任何运行期开关都不能把它打开。 */
    boolean mediaInfrastructureEnabled();

    /** 媒体付费分析（ASR、视频理解、片段复核、观众试映）是否可用。 */
    boolean mediaAnalysisEnabled();

    /** 配音合成与转写是否可用。 */
    boolean voiceEnabled();

    /** AI 视频生成是否可用。 */
    boolean videoEnabled();

    /** 测试和旧构造入口使用；生产装配始终注入读取运行期设置的实现。 */
    CreationCapabilityGate ALL_ENABLED = new CreationCapabilityGate() {
        @Override public boolean mediaInfrastructureEnabled() { return true; }
        @Override public boolean mediaAnalysisEnabled() { return true; }
        @Override public boolean voiceEnabled() { return true; }
        @Override public boolean videoEnabled() { return true; }
    };

    /** 旧构造入口按部署默认值模拟；只为保持既有调用语义，不代表生产开关。 */
    static CreationCapabilityGate defaults(boolean voice, boolean video) {
        return new CreationCapabilityGate() {
            @Override public boolean mediaInfrastructureEnabled() { return true; }
            @Override public boolean mediaAnalysisEnabled() { return true; }
            @Override public boolean voiceEnabled() { return voice; }
            @Override public boolean videoEnabled() { return video; }
        };
    }
}
