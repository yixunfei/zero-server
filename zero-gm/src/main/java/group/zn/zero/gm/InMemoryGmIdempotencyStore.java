package group.zn.zero.gm;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Bounded in-memory idempotency reference implementation for local/tests. */
public final class InMemoryGmIdempotencyStore implements GmIdempotencyStore {
    /** 本地参考实现的最大存活记录数；满时拒绝新 claim，不淘汰在途操作。 */
    private static final int CAPACITY = 4096;
    /** 请求键与当前记录，由实例锁保护。 */
    private final Map<String, Entry> entries = new HashMap<>();
    /** 每条记录只有一个到期索引，避免扫描所有键或累积失效节点。 */
    private final TreeMap<Instant, Set<String>> expirations = new TreeMap<>();

    /**
     * 原子认领请求并仅回收已过期终态；线程安全，在途操作不因 TTL 被回收。
     * @param key 请求键；非空、无控制字符，最多 256 字符。
     * @param fingerprint 请求指纹；约束同 key。
     * @param now 当前时间；不可为空。
     * @param ttl 终态保留预算提示；必须为正，实际到期由 complete/fail 提供。
     * @return 认领结果；容量已满时新键返回 CAPACITY_REJECTED，重复键继续返回原状态。
     * @throws IllegalArgumentException 参数无效时抛出。
     */
    @Override
    public synchronized Claim claim(
            final String key,
            final String fingerprint,
            final Instant now,
            final Duration ttl) {
        require(key, "key", 256);
        require(fingerprint, "fingerprint", 256);
        Objects.requireNonNull(now, "now");
        positive(ttl, "ttl");
        expire(now);
        Entry existing = entries.get(key);
        if (existing == null) {
            if (entries.size() >= CAPACITY) return new Claim(State.CAPACITY_REJECTED, null);
            // 在途操作不按保留 TTL 回收，否则原执行者仍可能提交副作用。
            entries.put(key, new Entry(fingerprint, State.IN_PROGRESS, null, null));
            return new Claim(State.CLAIMED, null);
        }
        if (!existing.fingerprint().equals(fingerprint)) {
            return new Claim(State.CONFLICT, null);
        }
        return new Claim(existing.state(), existing.response());
    }

    /** 保存已认领请求的成功结果；线程安全，更新其到期索引，缺少 claim 时抛出异常。 */
    @Override
    public synchronized void complete(
            final String key,
            final String fingerprint,
            final GmOperationResponse response,
            final Instant expiresAt) {
        update(key, fingerprint, State.COMPLETED, Objects.requireNonNull(response, "response"), expiresAt);
    }

    /** 保存已认领请求的失败状态；线程安全，更新到期索引，缺少 claim 时抛出异常。 */
    @Override
    public synchronized void fail(
            final String key,
            final String fingerprint,
            final Instant expiresAt) {
        update(key, fingerprint, State.FAILED, null, expiresAt);
    }

    /** @return 当前保留记录数，最多 4096；线程安全，过期记录在下次 claim 时回收。 */
    public synchronized int size() {
        return entries.size();
    }

    private void update(
            final String key,
            final String fingerprint,
            final State state,
            final GmOperationResponse response,
            final Instant expiresAt) {
        require(key, "key", 256);
        require(fingerprint, "fingerprint", 256);
        Objects.requireNonNull(expiresAt, "expiresAt");
        Entry existing = entries.get(key);
        if (existing == null || !existing.fingerprint().equals(fingerprint)) {
            throw new IllegalStateException("idempotency claim is missing or conflicts");
        }
        if (existing.state() != State.IN_PROGRESS) {
            throw new IllegalStateException("idempotency claim is already terminal");
        }
        entries.put(key, new Entry(fingerprint, state, response, expiresAt));
        index(key, expiresAt);
    }

    private void index(final String key, final Instant expiresAt) {
        expirations.computeIfAbsent(expiresAt, ignored -> new HashSet<>()).add(key);
    }

    private void expire(final Instant now) {
        while (!expirations.isEmpty() && !expirations.firstKey().isAfter(now)) {
            expirations.pollFirstEntry().getValue().forEach(entries::remove);
        }
    }

    private static void require(final String value, final String name, final int max) {
        if (value == null || value.isBlank() || value.length() > max
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " is invalid");
        }
    }

    private static void positive(final Duration value, final String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    /** 当前操作状态与到期时间的不可变快照。 */
    private record Entry(
            String fingerprint,
            State state,
            GmOperationResponse response,
            Instant expiresAt) {
    }
}
