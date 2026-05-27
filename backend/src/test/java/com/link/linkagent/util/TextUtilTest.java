package com.link.linkagent.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TextUtilTest {

    @Test
    void shouldDetectTextAfterTrimmingBlankCharacters() {
        assertThat(TextUtil.hasText("  Java  ")).isTrue();
        assertThat(TextUtil.hasText("   ")).isFalse();
        assertThat(TextUtil.hasText(null)).isFalse();
    }

    @Test
    void shouldTrimToDefaultWhenValueIsBlank() {
        assertThat(TextUtil.trimToDefault("  user-1  ", "default")).isEqualTo("user-1");
        assertThat(TextUtil.trimToDefault("   ", "default")).isEqualTo("default");
    }

    @Test
    void shouldAbbreviateWithSuffixOnlyWhenTextIsTooLong() {
        assertThat(TextUtil.abbreviateWithSuffix("abcdef", 3, "...")).isEqualTo("abc...");
        assertThat(TextUtil.abbreviateWithSuffix("abc", 3, "...")).isEqualTo("abc");
        assertThat(TextUtil.abbreviateWithSuffix("abcdef", 3, null)).isEqualTo("abc");
    }

    @Test
    void shouldBuildCollapsedPreview() {
        assertThat(TextUtil.preview("第一行\n第二行\t第三行", 6, "Empty session"))
                .isEqualTo("第一行 第二...");
        assertThat(TextUtil.preview("   ", 6, "Empty session"))
                .isEqualTo("Empty session");
    }
}
