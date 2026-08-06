package group.zn.zero.data.redis;

import java.util.Arrays;
import java.util.Objects;

/**
 * Redis 缓存信封。
 *
 * @param cacheVersion 缓存层版本号。
 * @param entityVersion 实体版本号。
 * @param schemaVersion 缓存 schema 版本。
 * @param codecName value codec 名称。
 * @param createdAtEpochMillis 创建时间戳。
 * @param expireAtEpochMillis 过期时间戳。
 * @param negative 是否负缓存。
 * @param payload 缓存 payload；负缓存时为空数组。
 * @author zn
 */
public record RedisCacheEnvelope(
        long cacheVersion,
        long entityVersion,
        int schemaVersion,
        String codecName,
        long createdAtEpochMillis,
        long expireAtEpochMillis,
        boolean negative,
        byte[] payload) {

    /**
     * 创建 Redis 缓存信封。
     *
     * @throws NullPointerException 当 codec 名称或 payload 为空时抛出。
     * @throws IllegalArgumentException 当版本号或时间戳非法时抛出。
     */
    public RedisCacheEnvelope {
        requireNonNegative(cacheVersion, "cacheVersion");
        requireNonNegative(entityVersion, "entityVersion");
        if (schemaVersion <= 0) {
            throw new IllegalArgumentException("schemaVersion must be positive");
        }
        codecName = requireText(codecName, "codecName");
        requireNonNegative(createdAtEpochMillis, "createdAtEpochMillis");
        requireNonNegative(expireAtEpochMillis, "expireAtEpochMillis");
        payload = Arrays.copyOf(Objects.requireNonNull(payload, "payload"), payload.length);
        if (negative && payload.length > 0) {
            throw new IllegalArgumentException("negative cache envelope payload must be empty");
        }
    }

    /**
     * 返回 payload 副本。
     *
     * @return payload 副本；不可为空；线程安全。
     */
    @Override
    public byte[] payload() {
        return Arrays.copyOf(payload, payload.length);
    }

    private static void requireNonNegative(final long value, final String name) {
        if (value < 0L) {
            throw new IllegalArgumentException(name + " must be non-negative");
        }
    }

    private static String requireText(final String value, final String name) {
        String current = Objects.requireNonNull(value, name);
        if (current.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return current;
    }
}
