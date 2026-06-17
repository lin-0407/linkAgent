package com.link.linkagent.settings.service;

import com.link.linkagent.knowledge.config.KnowledgeHybridStore;
import com.link.linkagent.knowledge.config.KnowledgeVectorStore;
import com.link.linkagent.settings.dto.ConnectivityCheckResponse;
import com.link.linkagent.settings.dto.ConnectivityItemResponse;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;

/**
 * 设置页基础设施连通性检测。
 * 检测保持轻量：只验证连接或 Bean 是否存在，不发起 LLM / Embedding 模型请求，避免设置页本身产生模型成本。
 */
@Service
public class SettingsConnectivityService {

    private static final String STATUS_UP = "UP";
    private static final String STATUS_DOWN = "DOWN";
    private static final String STATUS_DISABLED = "DISABLED";

    private final DataSource dataSource;
    private final ObjectProvider<StringRedisTemplate> redisTemplateProvider;
    private final KnowledgeVectorStore knowledgeVectorStore;
    private final KnowledgeHybridStore knowledgeHybridStore;
    private final ObjectProvider<EmbeddingModel> embeddingModelProvider;
    private final ObjectProvider<ChatModel> chatModelProvider;

    public SettingsConnectivityService(DataSource dataSource,
                                       ObjectProvider<StringRedisTemplate> redisTemplateProvider,
                                       KnowledgeVectorStore knowledgeVectorStore,
                                       KnowledgeHybridStore knowledgeHybridStore,
                                       ObjectProvider<EmbeddingModel> embeddingModelProvider,
                                       ObjectProvider<ChatModel> chatModelProvider) {
        this.dataSource = dataSource;
        this.redisTemplateProvider = redisTemplateProvider;
        this.knowledgeVectorStore = knowledgeVectorStore;
        this.knowledgeHybridStore = knowledgeHybridStore;
        this.embeddingModelProvider = embeddingModelProvider;
        this.chatModelProvider = chatModelProvider;
    }

    public ConnectivityCheckResponse check() {
        List<ConnectivityItemResponse> items = new ArrayList<>();
        items.add(checkMysql());
        items.add(checkRedis());
        items.add(checkEmbeddingModel());
        items.add(checkChatModel());
        items.add(checkKnowledgeParentVectorStore());
        items.add(checkKnowledgeChildVectorStore());
        items.add(checkKnowledgeHybridStore());
        return new ConnectivityCheckResponse(items);
    }

    private ConnectivityItemResponse checkMysql() {
        try (Connection connection = dataSource.getConnection()) {
            boolean valid = connection.isValid(2);
            return valid
                    ? up("mysql", "MySQL", "连接正常")
                    : down("mysql", "MySQL", "连接校验未通过");
        } catch (Exception exception) {
            return down("mysql", "MySQL", exception.getMessage());
        }
    }

    private ConnectivityItemResponse checkRedis() {
        StringRedisTemplate redisTemplate = redisTemplateProvider.getIfAvailable();
        if (redisTemplate == null) {
            return disabled("redis", "Redis", "当前没有 StringRedisTemplate Bean，可能未启用 Redis 自动配置");
        }
        try {
            redisTemplate.execute(connection -> {
                connection.serverCommands().info();
                return null;
            });
            return up("redis", "Redis", "连接正常");
        } catch (Exception exception) {
            return down("redis", "Redis", exception.getMessage());
        }
    }

    private ConnectivityItemResponse checkEmbeddingModel() {
        return embeddingModelProvider.getIfAvailable() == null
                ? disabled("embedding-model", "EmbeddingModel", "当前没有 EmbeddingModel Bean，向量能力会降级")
                : up("embedding-model", "EmbeddingModel", "Bean 已创建，未发起试算请求");
    }

    private ConnectivityItemResponse checkChatModel() {
        return chatModelProvider.getIfAvailable() == null
                ? down("chat-model", "ChatModel", "当前没有 ChatModel Bean，LLM 调用不可用")
                : up("chat-model", "ChatModel", "Bean 已创建，未发起模型请求");
    }

    private ConnectivityItemResponse checkKnowledgeParentVectorStore() {
        return knowledgeVectorStore.isReady()
                ? up("knowledge-parent-vector", "知识库父向量库", "父集合向量库已就绪")
                : disabled("knowledge-parent-vector", "知识库父向量库", "父集合向量库未就绪，案例检索会降级");
    }

    private ConnectivityItemResponse checkKnowledgeChildVectorStore() {
        return knowledgeVectorStore.isChildReady()
                ? up("knowledge-child-vector", "知识库子向量库", "子集合向量库已就绪")
                : disabled("knowledge-child-vector", "知识库子向量库", "子集合向量库未就绪，子条目召回会跳过");
    }

    private ConnectivityItemResponse checkKnowledgeHybridStore() {
        return knowledgeHybridStore.isReady()
                ? up("knowledge-hybrid-vector", "知识库 hybrid 向量库", "hybrid 客户端已就绪")
                : disabled("knowledge-hybrid-vector", "知识库 hybrid 向量库", "hybrid 向量库未就绪，原生混合检索不可用");
    }

    private ConnectivityItemResponse up(String key, String name, String message) {
        return new ConnectivityItemResponse(key, name, STATUS_UP, safeMessage(message));
    }

    private ConnectivityItemResponse down(String key, String name, String message) {
        return new ConnectivityItemResponse(key, name, STATUS_DOWN, safeMessage(message));
    }

    private ConnectivityItemResponse disabled(String key, String name, String message) {
        return new ConnectivityItemResponse(key, name, STATUS_DISABLED, safeMessage(message));
    }

    private String safeMessage(String message) {
        if (message == null || message.isBlank()) {
            return "未返回详细信息";
        }
        return message.length() > 240 ? message.substring(0, 240) + "..." : message;
    }
}
