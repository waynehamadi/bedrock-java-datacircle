package dev.datacircle.bedrock;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import software.amazon.awssdk.core.document.Document;

/**
 * Turns the SDK's {@link Document} into the values {@link Json} gives (a {@link Map}, a {@link List}, a
 * {@link BigDecimal}, a {@link String}, a {@link Boolean} or {@code null}) and back.
 */
final class Documents {

    private Documents() {
    }

    /** A document of JSON text, as a tool use's input or a tool's input schema. */
    static Document fromJson(String json) {
        return fromValue(Json.parse(json));
    }

    static Document fromValue(Object value) {
        if (value == null) {
            return Document.fromNull();
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Document> members = new LinkedHashMap<>();
            map.forEach((key, member) -> members.put(String.valueOf(key), fromValue(member)));
            return Document.fromMap(members);
        }
        if (value instanceof List<?> list) {
            List<Document> items = new ArrayList<>();
            list.forEach(item -> items.add(fromValue(item)));
            return Document.fromList(items);
        }
        if (value instanceof BigDecimal number) {
            return Document.fromNumber(number);
        }
        if (value instanceof Boolean bool) {
            return Document.fromBoolean(bool);
        }
        return Document.fromString(String.valueOf(value));
    }

    static Object toValue(Document document) {
        if (document == null || document.isNull()) {
            return null;
        }
        if (document.isMap()) {
            Map<String, Object> map = new LinkedHashMap<>();
            document.asMap().forEach((key, member) -> map.put(key, toValue(member)));
            return map;
        }
        if (document.isList()) {
            List<Object> list = new ArrayList<>();
            document.asList().forEach(item -> list.add(toValue(item)));
            return list;
        }
        if (document.isNumber()) {
            return document.asNumber().bigDecimalValue();
        }
        if (document.isBoolean()) {
            return document.asBoolean();
        }
        return document.asString();
    }
}
