package group.zn.zero.ownership;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/** 为任意状态端口增加有界准入；不创建线程，也不改变底层提交语义。 */
public final class CapacityBoundStateStore<T> implements FencedStateStore<T> {
    private final FencedStateStore<T> delegate;
    private final CapacityBudget budget;
    private final Duration waitTimeout;

    /** 创建容量装饰器；等待超时同时作为队列等待和关闭边界。 */
    public CapacityBoundStateStore(final FencedStateStore<T> delegate,
            final CapacityBudget budget, final Duration waitTimeout) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.budget = Objects.requireNonNull(budget, "budget");
        this.waitTimeout = requireTimeout(waitTimeout);
    }

    /** 读取状态前获取容量许可，超时或排空时确定性拒绝。 */
    @Override
    public Optional<StateSnapshot<T>> read(final OwnershipResource resource) {
        try (CapacityBudget.Permit ignored = acquirePermit()) {
            return delegate.read(resource);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new CapacityRejectedException("capacity wait interrupted");
        }
    }

    /** 提交状态前获取容量许可，底层结果原样返回。 */
    @Override
    public CommitResult<T> commit(final StateCommitRequest<T> request) {
        try (CapacityBudget.Permit ignored = acquirePermit()) {
            return delegate.commit(request);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new CapacityRejectedException("capacity wait interrupted");
        }
    }

    /** 使用调用方提供的等待期限执行一次提交。 */
    public CommitResult<T> commit(final StateCommitRequest<T> request, final Duration timeout) {
        Objects.requireNonNull(request, "request");
        try (CapacityBudget.Permit ignored = budget.acquire(requireTimeout(timeout))) {
            return delegate.commit(request);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new CapacityRejectedException("capacity wait interrupted");
        }
    }

    /** 返回底层预算，供优雅关闭和观察器使用。 */
    public CapacityBudget budget() {
        return budget;
    }

    private CapacityBudget.Permit acquirePermit() throws InterruptedException {
        return budget.acquire(waitTimeout);
    }

    private static Duration requireTimeout(final Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be non-negative");
        }
        return timeout;
    }
}
