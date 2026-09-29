package group.zn.zero.net.kcp;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import static group.zn.zero.net.kcp.KcpSessionOwner.Phase.ACTIVE;
import static group.zn.zero.net.kcp.KcpSessionOwner.Phase.FROZEN;
import static group.zn.zero.net.kcp.KcpSessionOwner.Phase.PENDING;
import static group.zn.zero.net.kcp.KcpSessionOwner.Phase.RELEASED;

/** 单 JVM 线性化所有权存储；短锁、有限记录、无工作线程，可注入时钟用于故障测试。 @author zn */
public final class InMemoryKcpSessionStore implements KcpSessionStore {
    /** 包含代际墓碑的有界记录。 */
    private final Map<Integer, Entry> entries = new HashMap<>();
    /** 权威存储时钟。 */
    private final Clock clock;
    /** 记录上限，容量耗尽必须背压。 */
    private final int capacity;
    /** 下次摊销墓碑清理时间。 */
    private Instant nextSweep = Instant.MIN;
    /** 创建默认 65536 条记录的隔离存储。 */
    public InMemoryKcpSessionStore() { this(Clock.systemUTC(), 65536); }
    /** @param clock 权威时钟。 @param capacity 活租约和墓碑总上限；非法参数拒绝，无网络副作用。 */
    public InMemoryKcpSessionStore(final Clock clock, final int capacity) {
        this.clock = Objects.requireNonNull(clock);
        if (capacity < 1) throw new IllegalArgumentException("capacity must be positive");
        this.capacity = capacity;
    }
    /** {@inheritDoc} */
    @Override public synchronized CompletionStage<Boolean> acquire(final int conv, final String owner,
            final long generation, final Duration lease) {
        validate(conv, owner, generation, lease); Instant now = clock.instant(); sweep(now);
        Entry old = entries.get(conv);
        if (old != null && (live(old, now) || generation <= old.value.generation())
                || old == null && entries.size() >= capacity) return result(false);
        entries.put(conv, entry(conv, owner, generation, lease, ACTIVE, null)); return result(true);
    }
    /** {@inheritDoc} */
    @Override public synchronized CompletionStage<Boolean> renew(final int conv, final String owner,
            final long generation, final Duration lease) {
        validate(conv, owner, generation, lease); Entry old = entries.get(conv);
        if (!matches(old, owner, generation) || old.value.phase() != ACTIVE && old.value.phase() != FROZEN) return result(false);
        entries.put(conv, entry(conv, owner, generation, lease, old.value.phase(), old.snapshot)); return result(true);
    }
    /** {@inheritDoc} */
    @Override public synchronized CompletionStage<Boolean> release(final int conv, final String owner, final long generation) {
        KcpSessionOwner.requireIdentity(conv, owner, generation); Entry old = entries.get(conv);
        if (old == null || !old.value.owner().equals(owner) || old.value.generation() != generation) return result(false);
        entries.put(conv, new Entry(new KcpSessionOwner(conv, owner, generation, clock.instant(), RELEASED), null,
                clock.instant().plus(Duration.ofHours(24)))); return result(true);
    }
    /** {@inheritDoc} */
    @Override public synchronized CompletionStage<Boolean> freeze(final int conv, final String owner, final long generation) {
        return change(conv, owner, generation, ACTIVE, FROZEN);
    }
    /** {@inheritDoc} */
    @Override public synchronized CompletionStage<Boolean> unfreeze(final int conv, final String owner, final long generation) {
        return change(conv, owner, generation, FROZEN, ACTIVE);
    }
    /** {@inheritDoc} */
    @Override public synchronized CompletionStage<Boolean> migrate(final KcpSessionSnapshot snapshot,
            final String targetOwner, final long targetGeneration, final Duration lease) {
        Objects.requireNonNull(snapshot); validate(snapshot.conv(), targetOwner, targetGeneration, lease);
        Entry old = entries.get(snapshot.conv());
        if (!matches(old, snapshot.owner(), snapshot.generation()) || old.value.phase() != FROZEN
                || targetGeneration <= snapshot.generation() || targetOwner.equals(snapshot.owner())
                || !snapshot.expiresAt().isAfter(clock.instant())) return result(false);
        entries.put(snapshot.conv(), entry(snapshot.conv(), targetOwner, targetGeneration, lease, PENDING, snapshot)); return result(true);
    }
    /** {@inheritDoc} */
    @Override public synchronized CompletionStage<Boolean> claim(final int conv, final String owner,
            final long generation, final Duration lease) {
        validate(conv, owner, generation, lease); Entry old = entries.get(conv);
        if (!matches(old, owner, generation) || old.value.phase() != PENDING) return result(false);
        entries.put(conv, entry(conv, owner, generation, lease, ACTIVE, null)); return result(true);
    }
    /** {@inheritDoc} */
    @Override public synchronized CompletionStage<KcpSessionOwner> owner(final int conv) {
        Entry old = entries.get(conv); return CompletableFuture.completedFuture(live(old, clock.instant()) ? old.value : null);
    }
    /** {@inheritDoc} */
    @Override public synchronized CompletionStage<KcpSessionSnapshot> load(final int conv) {
        Entry old = entries.get(conv);
        return CompletableFuture.completedFuture(live(old, clock.instant()) && old.value.phase() == PENDING ? old.snapshot : null);
    }
    private CompletionStage<Boolean> change(final int conv, final String owner, final long generation,
            final KcpSessionOwner.Phase expected, final KcpSessionOwner.Phase next) {
        KcpSessionOwner.requireIdentity(conv, owner, generation); Entry old = entries.get(conv);
        if (!matches(old, owner, generation) || old.value.phase() != expected) return result(false);
        entries.put(conv, new Entry(new KcpSessionOwner(conv, owner, generation, old.value.leaseExpiresAt(), next), old.snapshot, old.retainUntil));
        return result(true);
    }
    private Entry entry(final int conv, final String owner, final long generation, final Duration lease,
            final KcpSessionOwner.Phase phase, final KcpSessionSnapshot snapshot) {
        Instant expiry = clock.instant().plus(lease);
        return new Entry(new KcpSessionOwner(conv, owner, generation, expiry, phase), snapshot, expiry.plus(Duration.ofHours(24)));
    }
    private boolean matches(final Entry entry, final String owner, final long generation) {
        return live(entry, clock.instant()) && entry.value.owner().equals(owner) && entry.value.generation() == generation;
    }
    private static boolean live(final Entry entry, final Instant now) {
        return entry != null && entry.value.phase() != RELEASED && entry.value.leaseExpiresAt().isAfter(now);
    }
    private void sweep(final Instant now) {
        if (!now.isBefore(nextSweep)) { entries.values().removeIf(entry -> !entry.retainUntil.isAfter(now)); nextSweep = now.plusSeconds(60); }
    }
    private static void validate(final int conv, final String owner, final long generation, final Duration lease) {
        KcpSessionOwner.requireIdentity(conv, owner, generation); KcpSessionServices.requireLease(lease);
    }
    private static CompletionStage<Boolean> result(final boolean result) { return CompletableFuture.completedFuture(result); }
    /** 不可变存储项。 @author zn */
    private record Entry(KcpSessionOwner value, KcpSessionSnapshot snapshot, Instant retainUntil) { }
}
