package group.zn.zero.statesync;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Immutable synchronization envelope. */
public record SyncEnvelope(String sceneId, String observerId, long syncSeq, long baselineVersion,
                           long stateVersion, Kind kind, Map<String, Object> payload, String payloadHash) {
    /** 兼容旧调用方，使用规范化 payload 计算摘要。 */
    public SyncEnvelope(final String sceneId, final String observerId, final long syncSeq,
            final long baselineVersion, final long stateVersion, final Kind kind,
            final Map<String, Object> payload) {
        this(sceneId, observerId, syncSeq, baselineVersion, stateVersion, kind, payload, null);
    }

    public SyncEnvelope {
        if (sceneId == null || observerId == null || kind == null || payload == null) throw new NullPointerException();
        payload = Map.copyOf(payload);
        String computedHash = hashPayload(payload);
        payloadHash = payloadHash == null ? computedHash : requireHash(payloadHash, computedHash);
    }

    private static String requireHash(final String value, final String computedHash) {
        if (value.isBlank()) throw new IllegalArgumentException("payloadHash must not be blank");
        if (!computedHash.equalsIgnoreCase(value)) {
            throw new IllegalArgumentException("payloadHash does not match payload");
        }
        return computedHash;
    }

    private static String hashPayload(final Object value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(canonical(value).getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required", impossible);
        }
    }

    @SuppressWarnings("unchecked")
    private static String canonical(final Object value) {
        if (value == null) return "null";
        if (value instanceof Map<?, ?> map) {
            TreeMap<String, Object> sorted = new TreeMap<>();
            map.forEach((key, entry) -> sorted.put(String.valueOf(key), entry));
            return sorted.entrySet().stream()
                    .map(entry -> escape(entry.getKey()) + ":" + canonical(entry.getValue()))
                    .collect(java.util.stream.Collectors.joining(",", "{", "}"));
        }
        if (value instanceof Collection<?> collection) {
            return collection.stream().map(SyncEnvelope::canonical)
                    .collect(java.util.stream.Collectors.joining(",", "[", "]"));
        }
        if (value.getClass().isArray()) return escape(java.util.Arrays.deepToString(new Object[]{value}));
        return escape(Objects.toString(value));
    }

    private static String escape(final String value) {
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    public enum Kind { SNAPSHOT, DELTA }
}
