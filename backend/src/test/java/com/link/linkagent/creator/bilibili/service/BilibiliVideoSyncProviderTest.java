package com.link.linkagent.creator.bilibili.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.link.linkagent.creator.bilibili.model.BilibiliVideoSyncPayload;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** B站同步脚本 JSON 契约测试，不执行网络请求或 Python 进程。 */
class BilibiliVideoSyncProviderTest {

    @Test
    void shouldParseScriptPayload() {
        String json = """
                {
                  "bilibiliUid": "27058248",
                  "nickname": "测试账号",
                  "hasMore": false,
                  "partial": false,
                  "videos": [{
                    "bvid": "BV1xx411c7mD",
                    "aid": 1,
                    "title": "测试视频",
                    "ownerUid": "27058248",
                    "rawSnapshot": "{}"
                  }],
                  "verificationResults": [{
                    "bvid": "BV1xx411c7mD",
                    "status": "FOUND",
                    "ownerUid": "27058248",
                    "message": "BV归属校验通过"
                  }],
                  "warnings": []
                }
                """;
        BilibiliVideoSyncProvider provider = new BilibiliVideoSyncProvider(new ObjectMapper());

        BilibiliVideoSyncPayload payload = provider.parsePayload(json);

        assertThat(payload.bilibiliUid()).isEqualTo("27058248");
        assertThat(payload.videos()).hasSize(1);
        assertThat(payload.verificationResults().getFirst().status()).isEqualTo("FOUND");
    }
}
