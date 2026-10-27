package com.link.linkagent.knowledge.service;

import com.google.gson.JsonObject;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.common.ConsistencyLevel;
import io.milvus.v2.service.collection.request.AddFieldReq;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.LoadCollectionReq;
import io.milvus.v2.service.vector.request.InsertReq;
import io.milvus.v2.service.vector.request.QueryReq;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

/** 只用于本轮隔离Milvus端口；为后端清理API准备和核对五种索引的测试文档，向量不是模型产物。 */
@EnabledIfEnvironmentVariable(named="REFERENCE_MILVUS_LIVE_URI", matches="http://127\\.0\\.0\\.1:19532")
class KnowledgeReferenceIndexCleanupLiveTest {
    @Test void seedOrVerifyFiveDeletionCollections() {
        var client = new MilvusClientV2(ConnectConfig.builder().uri(System.getenv("REFERENCE_MILVUS_LIVE_URI")).rpcDeadlineMs(15000).build());
        try {
            List<String> names = List.of("ref_auto_live_parent","ref_auto_live_item","ref_auto_live_chunk","ref_auto_live_hybrid","ref_auto_live_hybrid_item");
            List<String> expiredIds = List.of("expired-fixture","expired-item","expired-chunk","expired-fixture","expired-item");
            boolean seed = "seed".equals(System.getenv("REFERENCE_MILVUS_LIVE_STAGE"));
            for (int i=0;i<names.size();i++) {
                String name = names.get(i);
                if (seed) {
                    var schema = client.createSchema();
                    schema.addField(AddFieldReq.builder().fieldName("id").dataType(DataType.VarChar).isPrimaryKey(true).autoID(false).maxLength(64).build());
                    schema.addField(AddFieldReq.builder().fieldName("vector").dataType(DataType.FloatVector).dimension(2).build());
                    client.createCollection(CreateCollectionReq.builder().collectionName(name).collectionSchema(schema)
                            .indexParams(List.of(IndexParam.builder().fieldName("vector").indexType(IndexParam.IndexType.FLAT).metricType(IndexParam.MetricType.COSINE).build())).build());
                    JsonObject expired = new JsonObject(); expired.addProperty("id",expiredIds.get(i));
                    JsonObject retained = new JsonObject(); retained.addProperty("id","retained-id");
                    var vector = new com.google.gson.JsonArray(); vector.add(1.0); vector.add(0.0);
                    expired.add("vector",vector); retained.add("vector",vector.deepCopy());
                    client.insert(InsertReq.builder().collectionName(name).data(List.of(expired,retained)).build());
                    client.loadCollection(LoadCollectionReq.builder().collectionName(name).build());
                }
                var results = client.query(QueryReq.builder().collectionName(name).filter("id != \"\"")
                        .outputFields(List.of("id")).consistencyLevel(ConsistencyLevel.STRONG).build()).getQueryResults();
                assertThat(results).hasSize(seed ? 2 : 1);
                if (!seed) assertThat(results.toString()).contains("retained-id").doesNotContain(expiredIds.get(i));
            }
        } finally {
            client.close();
        }
    }
}
