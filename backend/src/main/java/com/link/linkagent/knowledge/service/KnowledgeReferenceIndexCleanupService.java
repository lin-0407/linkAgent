package com.link.linkagent.knowledge.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.link.linkagent.knowledge.config.KnowledgeRagProperties;
import com.link.linkagent.knowledge.model.ReferenceIndexCleanupRecord;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.vector.request.DeleteReq;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/** 删除无需 embedding，也不以 RAG 开关推断历史集合是否存在。 */
@Service
public class KnowledgeReferenceIndexCleanupService {
    private final KnowledgeRagProperties properties;
    private final ObjectMapper objectMapper;

    public KnowledgeReferenceIndexCleanupService(KnowledgeRagProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public void delete(ReferenceIndexCleanupRecord job) throws Exception {
        var settings = properties.getMilvus();
        var builder = ConnectConfig.builder().uri("http://" + settings.getHost() + ":" + settings.getPort())
                .dbName(settings.getDatabaseName()).connectTimeoutMs(1500).rpcDeadlineMs(2000);
        if (settings.getUsername() != null && !settings.getUsername().isBlank()) {
            builder.username(settings.getUsername()).password(settings.getPassword());
        }
        MilvusClientV2 client = new MilvusClientV2(builder.build());
        try {
            List<String> items = objectMapper.readValue(job.itemIds(), new TypeReference<>() { });
            List<String> chunks = objectMapper.readValue(job.chunkIds(), new TypeReference<>() { });
            deleteIds(client, properties.getCollectionName(), List.of(job.videoId()));
            deleteIds(client, properties.getChildCollectionName(), items);
            deleteIds(client, properties.getChunkCollectionName(), chunks);
            deleteIds(client, properties.getHybrid().getCollectionName(), List.of(job.videoId()));
            deleteIds(client, properties.getHybrid().getChildCollectionName(), items);
        } finally {
            client.close();
        }
    }

    private void deleteIds(MilvusClientV2 client, String collection, List<String> ids) {
        if (ids.isEmpty() || !Boolean.TRUE.equals(client.hasCollection(HasCollectionReq.builder().collectionName(collection).build()))) return;
        // 评论较多时按主键分批删除；各步均幂等，上一轮部分成功后可以安全重试。
        for (int start = 0; start < ids.size(); start += 200) {
            List<Object> batch = new ArrayList<>(ids.subList(start, Math.min(start + 200, ids.size())));
            client.delete(DeleteReq.builder().collectionName(collection).ids(batch).build());
        }
    }
}
