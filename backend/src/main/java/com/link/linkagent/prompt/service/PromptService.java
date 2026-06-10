package com.link.linkagent.prompt.service;

import com.link.linkagent.prompt.mapper.PromptTemplateMapper;
import com.link.linkagent.prompt.model.PromptTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * 提示词取词 / 改词服务。
 * 调用方按 key 取提示词正文，改词直接写库。
 * <p>
 * 不做内存缓存：本项目单用户、非公网部署，取词紧跟在一次秒级的大模型调用之前，
 * 而按唯一键查单行提示词是毫秒级、可忽略的开销——加缓存省下的成本可忽略，却要额外维护
 * 「改库后同步刷新缓存」的一致性，得不偿失。每次直接查库反而让「改完即时生效」天然成立：
 * 无论走接口改还是有人直接改库，下一次取词都拿到最新值，不用重启、也没有缓存过期的窗口。
 * 真遇到多实例部署 / 高并发再加缓存（先跑通再优化）。
 * <p>
 * 取不到时直接响亮报错（fail-loud），而不是静默兜底：数据库是提示词的唯一来源，
 * 查不到一定是种子没灌或被误删，当场暴露比偷偷用一个错值更安全、也更好排查。
 */
@Service
public class PromptService {

    private final PromptTemplateMapper promptTemplateMapper;

    public PromptService(PromptTemplateMapper promptTemplateMapper) {
        this.promptTemplateMapper = promptTemplateMapper;
    }

    /**
     * 按 key 取提示词正文：直接查库，查不到则响亮报错。
     */
    public String get(String key) {
        return promptTemplateMapper.findByKey(key)
                .map(PromptTemplate::getContent)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        "提示词模板 key=" + key + " 未配置，请确认 backend/src/main/resources/sql/init.sql 已重新执行并灌入种子"));
    }

    /**
     * 列出全部未删除提示词，给只读管理接口分组展示用。
     */
    public List<PromptTemplate> listAll() {
        return promptTemplateMapper.listAll();
    }

    /**
     * 改写一条提示词正文：直接写库。下一次 get 查库即读到新值，无需重启、也没有缓存要刷——这就是「改完即时生效」。
     * 库里没有这条 key（受影响行数为 0）时报 404、不静默放过，避免调用方以为改成功、实际一行没动。
     * 只更新已存在的 key、不凭空新建：key 与代码里的调用处一一对应，新建只会产生没人读的死数据。
     */
    public void update(String key, String content) {
        int affected = promptTemplateMapper.updateContentByKey(key, content);
        if (affected == 0) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "提示词模板 key=" + key + " 不存在，无法更新");
        }
    }
}
