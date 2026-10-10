package group.zn.zero.gm.rest;

import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** 严格的 GM REST 请求；仅接受已知字段，无字符串到布尔值转换。 @author zn */
record GmRestRequest(String text, String traceId, String correlationId, String idempotencyKey,
                     String command, String target, String approvalToken, String secondReviewer, boolean dryRun) {
    /** 字符串字段白名单；拒绝未知字段以发现关键参数的拼写错误。 */
    private static final Set<String> TEXT_FIELDS = Set.of(
            "traceId", "correlationId", "idempotencyKey", "command", "target", "approvalToken", "secondReviewer");

    /** 解析有界 UTF-8 请求；格式、重复字段、类型错误直接拒绝，无外部副作用。 */
    static GmRestRequest parse(final byte[] body) throws IOException {
        String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(body)).toString();
        Map<String, String> fields = new HashMap<>();
        Set<String> seen = new HashSet<>();
        Boolean dryRun = null;
        try (JsonReader reader = new JsonReader(new StringReader(text))) {
            reader.setStrictness(Strictness.STRICT);
            reader.beginObject();
            while (reader.hasNext()) {
                String name = reader.nextName();
                if (!seen.add(name)) throw new IllegalArgumentException("duplicate field");
                if ("dryRun".equals(name)) {
                    if (reader.peek() != JsonToken.BOOLEAN) throw new IllegalArgumentException("dryRun must be boolean");
                    dryRun = reader.nextBoolean();
                } else {
                    if (!TEXT_FIELDS.contains(name) || reader.peek() != JsonToken.STRING) {
                        throw new IllegalArgumentException("unknown field or invalid type");
                    }
                    String value = reader.nextString();
                    if (value.length() > 256) throw new IllegalArgumentException("field too long");
                    fields.put(name, value);
                }
            }
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT || dryRun == null) {
                throw new IllegalArgumentException("trailing content or missing dryRun");
            }
        }
        return new GmRestRequest(text, required(fields, "traceId"), required(fields, "correlationId"),
                required(fields, "idempotencyKey"), required(fields, "command"), required(fields, "target"),
                fields.getOrDefault("approvalToken", ""), fields.getOrDefault("secondReviewer", ""), dryRun);
    }

    private static String required(final Map<String, String> fields, final String name) {
        String value = fields.get(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("required field missing");
        return value;
    }
}
