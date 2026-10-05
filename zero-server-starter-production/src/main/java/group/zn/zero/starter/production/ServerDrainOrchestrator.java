package group.zn.zero.starter.production;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Small server-level admission/drain coordinator. It intentionally does not
 * own GameRuntime and therefore does not alter its lifecycle API.
 */
public final class ServerDrainOrchestrator {
    /** 停止入口接收的回调，只由 drain 发起者调用。 */
    private final Runnable stopAdmission;
    /** 排空超时后的资源关闭回调，只调用一次。 */
    private final Runnable forceClose;
    /** 可无锁读取的服务状态。 */
    private final AtomicReference<ServerDrainState> state = new AtomicReference<>(ServerDrainState.NEW);
    /** 在途请求数量，同时作为排空等待通知锁。 */
    private final AtomicInteger inFlight = new AtomicInteger();
    /** 最近状态改变的时间。 */
    private volatile Instant stateChangedAt = Instant.now();
    /** 同一次排空的共享结果，由实例锁保护发布。 */
    private CompletableFuture<Boolean> drainResult;
    /** 排空发起线程，用于阻止关闭回调自等待；由实例锁保护。 */
    private Thread drainOwner;

    /** 创建协调器；回调不可为空，调用方管理业务与资源生命周期。 */
    public ServerDrainOrchestrator(final Runnable stopAdmission, final Runnable forceClose) {
        this.stopAdmission = Objects.requireNonNull(stopAdmission, "stopAdmission");
        this.forceClose = Objects.requireNonNull(forceClose, "forceClose");
    }

    /** @return 当前状态；线程安全，不修改状态。 */
    public ServerDrainState state() {
        return state.get();
    }

    /** 首次启动接收请求；线程安全，停止后的实例不可重新启动。 */
    public synchronized void start() {
        if (state.get() == ServerDrainState.NEW) {
            transition(ServerDrainState.RUNNING);
        }
    }

    /**
     * 停止接收并等待在途请求；并发调用共享一次关闭操作，回调不持有内部锁。
     * @param timeout 本调用最大排空等待时间；非负，不包括关闭回调耗时。
     * @return 正常排空为 true，强制关闭、跟随者超时、中断或回调重入为 false。
     * @throws IllegalArgumentException 超时时间为负时抛出。
     * @throws ArithmeticException 超时时间超出纳秒范围时抛出，不改变状态。
     * @throws RuntimeException 关闭回调失败时原样抛出；状态为 FAILED。
     */
    public boolean drain(final Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must not be negative");
        }
        long timeoutNanos = timeout.toNanos();
        CompletableFuture<Boolean> result;
        boolean owner;
        synchronized (this) {
            owner = drainResult == null;
            if (owner) {
                drainResult = new CompletableFuture<>();
                drainOwner = Thread.currentThread();
                transition(ServerDrainState.DRAINING);
            } else if (drainOwner == Thread.currentThread()) {
                return false;
            }
            result = drainResult;
        }
        if (!owner) return awaitDrain(result, timeoutNanos);
        try {
            stopAdmission.run();
            boolean drained;
            try {
                drained = awaitZero(timeoutNanos);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                drained = false;
            }
            if (!drained) forceClose.run();
            transition(ServerDrainState.STOPPED);
            result.complete(drained);
            return drained;
        } catch (RuntimeException | Error failure) {
            transition(ServerDrainState.FAILED);
            result.completeExceptionally(failure);
            throw failure;
        } finally {
            synchronized (this) { drainOwner = null; }
        }
    }

    /** Attempts to admit one request; returns null while not RUNNING. */
    public RequestLease tryAcquire() {
        while (state.get() == ServerDrainState.RUNNING) {
            inFlight.incrementAndGet();
            if (state.get() == ServerDrainState.RUNNING) return new RequestLease();
            synchronized (inFlight) {
                inFlight.decrementAndGet();
                inFlight.notifyAll();
            }
        }
        return null;
    }

    /** @return 当前健康快照；不可变、线程安全，不修改状态。 */
    public synchronized ServerHealthSnapshot healthSnapshot() {
        return new ServerHealthSnapshot(state(), inFlight.get(), stateChangedAt);
    }

    private boolean awaitDrain(final CompletableFuture<Boolean> result, final long timeoutNanos) {
        try {
            return result.get(timeoutNanos, TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        } catch (TimeoutException timeout) {
            return false;
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new CompletionException(cause);
        }
    }

    private boolean awaitZero(final long timeoutNanos) throws InterruptedException {
        long nanos = timeoutNanos;
        synchronized (inFlight) {
            while (inFlight.get() != 0) {
                if (nanos <= 0) return false;
                long before = System.nanoTime();
                TimeUnit.NANOSECONDS.timedWait(inFlight, nanos);
                nanos -= System.nanoTime() - before;
            }
            return true;
        }
    }

    private synchronized void transition(final ServerDrainState next) {
        state.set(next);
        stateChangedAt = Instant.now();
    }

    /** 已接收请求的幂等释放句柄；可跨线程关闭。 */
    public final class RequestLease implements AutoCloseable {
        /** 释放标志，由 inFlight 锁保护。 */
        private boolean closed;
        private RequestLease() { }
        /** 首次关闭减少在途计数并唤醒排空等待；线程安全。 */
        @Override public void close() {
            synchronized (inFlight) {
                if (!closed) {
                    closed = true;
                    inFlight.decrementAndGet();
                    inFlight.notifyAll();
                }
            }
        }
    }
}
