package group.zn.zero.ownership;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** 无外部依赖的路由实现，适合单进程原型和契约测试。 */
public final class InMemoryOwnershipRouter implements OwnershipRouter {
    private final Clock clock;
    private final Map<OwnershipResource, OwnershipRoute> routes = new HashMap<>();

    /** 使用系统 UTC 时钟创建路由表。 */
    public InMemoryOwnershipRouter() {
        this(Clock.systemUTC());
    }

    /** 使用可控时钟创建路由表。 */
    public InMemoryOwnershipRouter(final Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public synchronized Optional<OwnershipRoute> resolve(final OwnershipResource resource) {
        OwnershipRoute route = routes.get(Objects.requireNonNull(resource, "resource"));
        return route != null && route.validAt(clock.instant()) ? Optional.of(route) : Optional.empty();
    }

    @Override
    public synchronized OwnershipRoute publish(final OwnershipLease lease, final String endpoint) {
        Objects.requireNonNull(lease, "lease");
        Instant now = clock.instant();
        if (!lease.validAt(now)) {
            throw new OwnershipException("cannot publish an expired ownership lease");
        }
        OwnershipRoute current = routes.get(lease.resource());
        // 过期路由仍保留 epoch 栅栏；同代同 owner 必须允许续租刷新。
        if (current != null && (current.epoch() > lease.epoch()
                || !current.owner().equals(lease.owner())
                && (current.validAt(now) || current.epoch() == lease.epoch()))) {
            throw new OwnershipException("stale owner route");
        }
        OwnershipRoute route = new OwnershipRoute(lease.resource(), lease.owner(), lease.epoch(), endpoint,
                lease.expiresAt());
        routes.put(lease.resource(), route);
        return route;
    }

    @Override
    public synchronized boolean withdraw(final OwnershipLease lease) {
        Objects.requireNonNull(lease, "lease");
        OwnershipRoute current = routes.get(lease.resource());
        if (current == null || current.epoch() != lease.epoch() || !current.owner().equals(lease.owner())) {
            return false;
        }
        routes.remove(lease.resource());
        return true;
    }
}
