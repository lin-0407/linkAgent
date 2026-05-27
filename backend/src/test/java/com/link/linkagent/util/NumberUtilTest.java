package com.link.linkagent.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NumberUtilTest {

    @Test
    void shouldNormalizeLimitWithDefaultAndMaxValue() {
        assertThat(NumberUtil.limitOrDefault(null, 20, 100)).isEqualTo(20);
        assertThat(NumberUtil.limitOrDefault(0, 20, 100)).isEqualTo(20);
        assertThat(NumberUtil.limitOrDefault(30, 20, 100)).isEqualTo(30);
        assertThat(NumberUtil.limitOrDefault(200, 20, 100)).isEqualTo(100);
    }
}
