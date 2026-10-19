package com.link.linkagent.knowledge.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.link.linkagent.knowledge.model.ReferenceVideoImportRequest;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ReferenceVideoImportJsonTest {
    @Test void integerCountsAndMissingOptionalCountsAreKept() throws Exception {
        var stats = new ObjectMapper().readValue("{\"view\":100,\"coin\":0}",ReferenceVideoImportRequest.VideoStats.class);
        assertThat(stats.view()).isEqualTo(100L);
        assertThat(stats.coin()).isZero();
        assertThat(stats.favorite()).isNull();
    }
    @Test void fractionsStringsBooleansAndOverflowAreRejected() {
        for (String value : new String[]{"1.9","\"100\"","true","9223372036854775808"}) {
            assertThatThrownBy(() -> new ObjectMapper().readValue("{\"view\":"+value+"}",ReferenceVideoImportRequest.VideoStats.class)).isInstanceOf(Exception.class);
        }
    }
}
