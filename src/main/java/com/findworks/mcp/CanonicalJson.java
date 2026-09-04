package com.findworks.mcp;

import java.util.ArrayList;
import java.util.TreeMap;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

final class CanonicalJson {
    private CanonicalJson() {}

    static String write(ObjectMapper mapper, JsonNode node) {
        try {
            return mapper.writeValueAsString(value(node));
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid JSON", exception);
        }
    }

    private static Object value(JsonNode node) {
        if (node.isObject()) {
            var result = new TreeMap<String, Object>();
            node.propertyNames().forEach(name -> result.put(name, value(node.get(name))));
            return result;
        }
        if (node.isArray()) {
            var result = new ArrayList<>();
            node.forEach(item -> result.add(value(item)));
            return result;
        }
        if (node.isNull()) return null;
        if (node.isBoolean()) return node.asBoolean();
        if (node.isIntegralNumber()) return node.bigIntegerValue();
        if (node.isFloatingPointNumber()) return node.decimalValue();
        return node.asText();
    }
}
