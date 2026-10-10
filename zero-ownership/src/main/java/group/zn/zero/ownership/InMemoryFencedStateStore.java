package group.zn.zero.ownership;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** 无外部依赖的 fencing 状态实现，适合单进程原型和契约测试。 */
public final class InMemoryFencedStateStore<T> implements FencedStateStore<T> {
    private static final int DEFAULT_REQUEST_CAPACITY = 4096;
    private final InMemoryOwnershipStore ownershipStore;
    private final Clock clock;
    private final int requestCapacity;
    /** 只保护本地状态与请求幂等表，不跨越 ownership 远程调用。 */
    private final Object stateLock = new Object();
    private final Map<OwnershipResource, StateSnapshot<T>> states = new HashMap<>();
    private final Map<RequestKey, StoredCommit<T>> requests = new HashMap<>();
    /** 每资源的归档 ID 水位；仅状态锁内访问，拒绝已释放收据的重放。 */
    private final Map<OwnershipResource, String> retiredThrough = new HashMap<>();

    /** 创建本地原子 fencing 状态存储；远程 ownership 必须使用对应的事务状态实现。
     * @throws IllegalArgumentException ownership 不是 InMemoryOwnershipStore 时抛出。 */
    public InMemoryFencedStateStore(final OwnershipStore ownershipStore) {
        this(ownershipStore, Clock.systemUTC(), DEFAULT_REQUEST_CAPACITY);
    }

    /** 创建使用指定时钟的本地状态存储，便于确定性测试。
     * @throws IllegalArgumentException ownership 不是 InMemoryOwnershipStore 时抛出。 */
    public InMemoryFencedStateStore(final OwnershipStore ownershipStore, final Clock clock) {
        this(ownershipStore, clock, DEFAULT_REQUEST_CAPACITY);
    }

    /** 创建使用指定时钟和请求收据容量的本地状态存储；容量满时拒绝新请求而不淘汰收据。 */
    public InMemoryFencedStateStore(final OwnershipStore ownershipStore, final Clock clock, final int requestCapacity) {
        Objects.requireNonNull(ownershipStore, "ownershipStore");
        if (!(ownershipStore instanceof InMemoryOwnershipStore local)) {
            throw new IllegalArgumentException("in-memory fenced state requires an in-memory ownership store");
        }
        this.ownershipStore = local;
        this.clock = Objects.requireNonNull(clock, "clock");
        if (requestCapacity <= 0) {
            throw new IllegalArgumentException("requestCapacity must be positive");
        }
        this.requestCapacity = requestCapacity;
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
        return ownershipStore.withCurrent(request.lease(), clock, () -> commitCurrent(request, key));
    }

    /**
     * 当前所有者确认结果已外部归档后压缩收据，线程安全，与 fencing/commit 原子排序。
     * 请求 ID 按 String.compareTo 排序；水位以下永久拒绝，新请求必须采用更大的 ID。
     * @param lease 当前有效租约。 @param requestId 已归档包含边界，不可空白。
     * @return 释放数量。 @throws OwnershipException 租约失效或水位回退。
     */
    public int acknowledgeRequestsThrough(final OwnershipLease lease, final String requestId) {
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(requestId, "requestId");
        if (requestId.isBlank()) throw new IllegalArgumentException("requestId");
        return ownershipStore.withCurrent(lease, clock, () -> {
            synchronized (stateLock) {
                String previous = retiredThrough.get(lease.resource());
                if (previous != null && requestId.compareTo(previous) < 0) throw new OwnershipException("watermark regression");
                int before = requests.size();
                requests.keySet().removeIf(key -> key.resource().equals(lease.resource())
                        && key.requestId().compareTo(requestId) <= 0);
                retiredThrough.put(lease.resource(), requestId);
                return before - requests.size();
            }
        });
    }

    /** ownership 锁先于状态锁获取，使 lease 的释放/接管与本地状态写线性化。 */
    private CommitResult<T> commitCurrent(final StateCommitRequest<T> request, final RequestKey key) {
        synchronized (stateLock) {
            String watermark = retiredThrough.get(request.resource());
            if (watermark != null && request.requestId().compareTo(watermark) <= 0) {
                throw new OwnershipException("request is before replay watermark");
            }
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
            if (requests.size() >= requestCapacity) {
                throw new OwnershipException("request receipt capacity exceeded");
            }
            StateVersion next = actual.next();
            StateSnapshot<T> updated = new StateSnapshot<>(next, request.payload());
            states.put(request.resource(), updated);
            CommitResult<T> result = new CommitResult<>(true, false, next, request.payload());
            requests.put(key, new StoredCommit<>(request.expectedVersion(), request.payload(), result));
            return result;
        }
    }

    private record RequestKey(OwnershipResource resource, String requestId) { }

    private record StoredCommit<T>(StateVersion expectedVersion, T payload, CommitResult<T> result) { }
}
