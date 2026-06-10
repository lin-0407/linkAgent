package com.link.linkagent.prompt;

import com.link.linkagent.prompt.service.PromptService;

/**
 * 测试用 PromptService 替身。
 * 各单测构造 Service 时传入此类，避免单测依赖数据库。
 * get(key) 返回可预测的占位串，方便测试断言"系统提示词被正确传入了 LLM 调用"：
 * 断言 lastSystemPrompt.contains("pre_publish.system") 比断言原文更稳定——
 * 原文一改测试就红，而 key 作为稳定标识不随提示词内容变化而变化。
 */
public class StubPromptService extends PromptService {

    public StubPromptService() {
        super(null);
    }

    @Override
    public String get(String key) {
        return "[test-prompt:" + key + "]";
    }
}
