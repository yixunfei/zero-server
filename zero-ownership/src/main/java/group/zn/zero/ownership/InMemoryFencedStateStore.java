package group.zn.zero.ownership;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** 无外部依赖的 fencing 状态实现，适合单进程原型和契约测试。 */
public final class InMemoryFencedStateStore<T> implements FencedStateStore<T> {
    private final OwnershipStore ownershipStore;
    private final Clock clock;
    /** 只保护本地状态与请求幂等表，不跨越 ownership 远程调用。 */
    private final Object stateLock = new Object();
    private final Map<OwnershipResource, StateSnapshot<T>> states = new HashMap<>();
    private final Map<RequestKey, StoredCommit<T>> requests = new HashMap<>();

    /** 创建绑定到 ownership 权威端口的状态存储。 */
    public InMemoryFencedStateStore(final OwnershipStore ownershipStore) {
        this(ownershipStore, Clock.systemUTC());
    }

    /** 创建使用指定时钟的状态存储，便于确定性测试。 */
    public InMemoryFencedStateStore(final OwnershipStore ownershipStore, final Clock clock) {
        this.ownershipStore = Objects.requireNonNull(ownershipStore, "ownershipStore");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public Optional<StateSnapshot<T>> read(final OwnershipResource resource) {
        synchronized (stateLock) {
            return Optional.ofNullable(states.get(Objects.requireNonNull(resource, "resource")));
        }
    }

    @Override
    public CommitResult<T> commit(final StateCommitRequest<T> request) {
        Objects.requireNonNull(request, "request");
        RequestKey key = new RequestKey(request.resource(), request.requestId());
        synchronized (stateLock) {
            StoredCommit<T> previous = requests.get(key);
            if (previous != null) {
                if (!Objects.equals(previous.payload(), request.payload())
                        || !previous.expectedVersion().equals(request.expectedVersion())) {
                    throw new OwnershipException("request id was reused with a different payload");
                }
                return new CommitResult<>(false, true, previous.result().newVersion(), previous.result().payload());
            }
        }
        OwnershipLease current = ownershipStore.current(request.resource(), clock.instant())
                .orElseThrow(() -> new OwnershipException("resource is not currently owned"));
        if (!sameLease(current, request.lease())) {
            throw new OwnershipException("stale owner epoch or process incarnation");
        }
        synchronized (stateLock) {
            StoredCommit<T> previous = requests.get(key);
            if (previous != null) {
                if (!Objects.equals(previous.payload(), request.payload())
                        || !previous.expectedVersion().equals(request.expectedVersion())) {
                    throw new OwnershipException("request id was reused with a different payload");
                }
                return new CommitResult<>(false, true, previous.result().newVersion(), previous.result().payload());
            }
            StateSnapshot<T> currentState = states.get(request.resource());
            StateVersion actual = currentState == null ? StateVersion.initial() : currentState.version();
            if (!actual.equals(request.expectedVersion())) {
                throw new OwnershipException("state version conflict");
            }
            StateVersion next = actual.next();
            StateSnapshot<T> updated = new StateSnapshot<>(next, request.payload());
            states.put(request.resource(), updated);
            CommitResult<T> result = new CommitResult<>(true, false, next, request.payload());
            requests.put(key, new StoredCommit<>(request.expectedVersion(), request.payload(), result));
            return result;
        }
    }

    private static boolean sameLease(final OwnershipLease left, final OwnershipLease right) {
        return left.epoch() == right.epoch() && left.resource().equals(right.resource())
                && left.owner().equals(right.owner());
    }

    private record RequestKey(OwnershipResource resource, String requestId) { }

    private record StoredCommit<T>(StateVersion expectedVersion, T payload, CommitResult<T> result) { }
}
