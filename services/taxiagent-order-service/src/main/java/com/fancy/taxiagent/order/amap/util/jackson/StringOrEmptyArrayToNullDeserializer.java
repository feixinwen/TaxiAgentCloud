package com.fancy.taxiagent.order.amap.util.jackson;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 兼容高德 API 某些字段偶发返回空数组 []（而不是空字符串/缺失）的情况。
 *
 * <p>规则：字符串原样返回；空数组返回 null；非空数组按逗号拼接；数字/布尔转文本；其它类型 null。</p>
 */
public class StringOrEmptyArrayToNullDeserializer extends JsonDeserializer<String> {

    @Override
    public String deserialize(JsonParser parser, DeserializationContext ctxt) throws IOException {
        JsonNode node = parser.getCodec().readTree(parser);
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isArray()) {
            if (node.isEmpty()) {
                return null;
            }
            List<String> parts = new ArrayList<>();
            for (JsonNode child : node) {
                if (child != null && !child.isNull()) {
                    String text = child.asText();
                    if (text != null && !text.isBlank()) {
                        parts.add(text);
                    }
                }
            }
            if (parts.isEmpty()) {
                return null;
            }
            return String.join(",", parts);
        }
        if (node.isNumber() || node.isBoolean()) {
            return node.asText();
        }
        return null;
    }
}
