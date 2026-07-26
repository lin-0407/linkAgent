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
    void shouldTrimToNullWhenValueIsBlank() {
        assertThat(TextUtil.trimToNull("  user-1  ")).isEqualTo("user-1");
        assertThat(TextUtil.trimToNull("   ")).isNull();
        assertThat(TextUtil.trimToNull(null)).isNull();
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

    @Test
    void shouldNormalizeExceptionMessageWithClassNameFallback() {
        assertThat(TextUtil.normalizeExceptionMessage(new IllegalStateException("  第一行\n第二行\t ")))
                .isEqualTo("第一行 第二行");
        assertThat(TextUtil.normalizeExceptionMessage(new IllegalArgumentException("   ")))
                .isEqualTo("IllegalArgumentException");
        assertThat(TextUtil.normalizeExceptionMessage(new IllegalStateException("abcdef"), 3))
                .isEqualTo("abc...");
        assertThat(TextUtil.normalizeExceptionMessage(null)).isNull();
    }
}
