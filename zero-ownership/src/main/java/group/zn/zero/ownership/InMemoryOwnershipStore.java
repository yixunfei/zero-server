package group.zn.zero.ownership;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** 无线程和外部依赖的 ownership 实现，适合单进程原型和契约测试。 */
public final class InMemoryOwnershipStore implements OwnershipStore {
    private final Clock clock;
    private final Map<OwnershipResource, OwnershipLease> leases = new HashMap<>();
    private final Map<OwnershipResource, Long> epochs = new HashMap<>();

    /** 使用 UTC 系统时钟创建实现。 */
    public InMemoryOwnershipStore() {
        this(Clock.systemUTC());
    }

    /** 使用可控时钟创建实现。 */
    public InMemoryOwnershipStore(final Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public synchronized OwnershipLease acquire(
            final OwnershipResource resource,
            final ProcessIncarnation owner,
            final Duration leaseDuration) {
        requireLeaseDuration(leaseDuration);
        OwnershipLease current = leases.get(Objects.requireNonNull(resource, "resource"));
        Instant now = clock.instant();
        if (current != null && current.validAt(now)) {
            throw new OwnershipException("resource is owned by another valid incarnation");
        }
        return issue(resource, owner, leaseDuration, now);
    }

    @Override
    public synchronized OwnershipLease renew(final OwnershipLease lease, final Duration leaseDuration) {
        requireLeaseDuration(leaseDuration);
        OwnershipLease current = requireCurrent(lease.resource());
        Instant now = clock.instant();
        requireMatch(current, lease, now, true);
        OwnershipLease renewed = new OwnershipLease(current.resource(), current.owner(), current.epoch(), now.plus(leaseDuration));
        leases.put(current.resource(), renewed);
        return renewed;
    }

    @Override
    public synchronized boolean release(final OwnershipLease lease) {
        Objects.requireNonNull(lease, "lease");
        OwnershipLease current = leases.get(lease.resource());
        if (current == null || !sameOwner(current, lease) || !current.validAt(clock.instant())) {
            return false;
        }
        leases.remove(lease.resource());
        return true;
    }

    @Override
    public synchronized OwnershipLease claim(
            final OwnershipResource resource,
            final ProcessIncarnation owner,
            final Duration leaseDuration) {
        requireLeaseDuration(leaseDuration);
        OwnershipLease current = leases.get(Objects.requireNonNull(resource, "resource"));
        Instant now = clock.instant();
        if (current != null && current.validAt(now)) {
            throw new OwnershipException("resource has not expired");
        }
        return issue(resource, owner, leaseDuration, now);
    }

    @Override
    public synchronized Optional<OwnershipLease> current(final OwnershipResource resource, final Instant now) {
        OwnershipLease current = leases.get(Objects.requireNonNull(resource, "resource"));
        return current != null && current.validAt(Objects.requireNonNull(now, "now"))
                ? Optional.of(current) : Optional.empty();
    }

    /** 在权威提交前校验租约，防止旧 owner 写入。 */
    public synchronized void assertCurrent(final OwnershipLease lease) {
        OwnershipLease current = requireCurrent(lease.resource());
        requireMatch(current, lease, clock.instant(), true);
    }

    private OwnershipLease issue(
            final OwnershipResource resource,
            final ProcessIncarnation owner,
            final Duration duration,
            final Instant now) {
        long epoch = epochs.merge(resource, 1L, Long::sum);
        OwnershipLease lease = new OwnershipLease(resource, Objects.requireNonNull(owner, "owner"), epoch, now.plus(duration));
        leases.put(resource, lease);
        return lease;
    }

    private OwnershipLease requireCurrent(final OwnershipResource resource) {
        OwnershipLease current = leases.get(Objects.requireNonNull(resource, "resource"));
        if (current == null) {
            throw new OwnershipException("resource has no lease");
        }
        return current;
    }

    private static void requireMatch(
            final OwnershipLease current,
            final OwnershipLease candidate,
            final Instant now,
            final boolean checkExpiry) {
        if (!sameOwner(current, candidate) || (checkExpiry && !current.validAt(now))) {
            throw new OwnershipException("stale owner epoch or process incarnation");
        }
    }

    private static boolean sameOwner(final OwnershipLease left, final OwnershipLease right) {
        return left.epoch() == right.epoch() && left.owner().equals(right.owner())
                && left.resource().equals(right.resource());
    }

    private static void requireLeaseDuration(final Duration duration) {
        Objects.requireNonNull(duration, "leaseDuration");
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("leaseDuration must be positive");
        }
    }
}
