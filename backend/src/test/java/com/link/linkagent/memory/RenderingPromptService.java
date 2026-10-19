package com.link.linkagent.memory;

import com.link.linkagent.prompt.service.PromptService;

import java.util.Map;

/**
 * 会把变量带进结果的测试替身。
 * <p>
 * {@link com.link.linkagent.prompt.StubPromptService} 返回固定串、不替换占位符，
 * 想断言「模型到底看到了什么」（批量抽取是否带上整批消息、打分是否带上这一轮对话）时会失真，
 * 这里按「key + 变量名: 变量值」的形状拼出来。
 */
class RenderingPromptService extends PromptService {

    RenderingPromptService() {
        super(null);
    }

    @Override
    public String get(String key) {
        return "[test-prompt:" + key + "]";
    }

    @Override
    public String render(String key, Map<String, String> vars) {
        StringBuilder rendered = new StringBuilder(get(key));
        for (Map.Entry<String, String> entry : vars.entrySet()) {
            rendered.append('\n').append(entry.getKey()).append(": ").append(entry.getValue());
        }
        return rendered.toString();
    }
}
