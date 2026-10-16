package com.link.linkagent.knowledge.model;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import java.io.IOException;

/** 不让Jackson把1.9截成1或把字符串当统计整数；仅约束参考视频，不改变其它业务DTO。 */
public class ReferenceVideoIntegerDeserializer extends StdDeserializer<Long> {
    public ReferenceVideoIntegerDeserializer() { super(Long.class); }
    @Override public Long deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT) {
            return context.reportInputMismatch(Long.class,"视频统计和时间戳必须是64位整数，不能是小数、字符串或布尔值");
        }
        return parser.getLongValue();
    }
}
