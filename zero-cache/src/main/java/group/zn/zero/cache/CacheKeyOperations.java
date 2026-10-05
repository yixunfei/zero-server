package group.zn.zero.cache;

import group.zn.zero.core.error.ZeroException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 分层缓存的短期键状态：读取代际隔离、同键异步写顺序和引用计数回收。
 * 所有锁仅保护内存登记；外部操作及完成回调均在锁外触发。不创建线程。
 * @param <K> 缓存键类型。
 * @author zn
 */
final class CacheKeyOperations<K> {
    /** 单键已接受写入上限，包含正在执行的一项。 */
    private static final int MAX_WRITES_PER_KEY = 1024;
    /** 全部键的已接受写入上限。 */
    private static final int MAX_WRITES = 4096;
    /** 只保存有在途操作的键，受本对象锁保护。 */
    private final Map<K, State> states = new HashMap<>();
    /** 正在执行或等待前序屏障的写入总数，受本对象锁保护。 */
    private int writes;
    /** 仅在当前线程完成屏障期间存在，防止同步完成链递归耗尽栈。 */
    private final ThreadLocal<CompletionDrain> completionDrain = new ThreadLocal<>();

    /** 开始读取；调用方必须在异步操作结束后 release。 */
    synchronized Token<K> read(final K key) {
        State state = states.computeIfAbsent(key, ignored -> new State());
        state.references++;
        return new Token<>(key, state, state.generation);
    }

    /** 释放在途引用；最后一个操作结束后不保留历史键或代际。 */
    synchronized void release(final Token<K> token) {
        if (--token.state.references == 0) states.remove(token.key, token.state);
    }

    /** 为待写回项保留原读取代际；已被显式更新覆盖的读取不能重新进入重试队列。 */
    synchronized boolean retain(final Token<K> token) {
        if (token.generation != token.state.generation) return false;
        token.state.references++;
        return true;
    }

    /**
     * 放弃迟到读取的代际；若期间已有显式变更，则保留更新后的代际。
     *
     * @param token 待读取身份；不可为空。
     */
    synchronized void abandon(final Token<K> token) {
        if (token.generation == token.state.generation) {
            token.state.generation++;
        }
    }

    /** 在短锁内验证读取代际并执行本地缓存合并，不得传入外部回调。 */
    synchronized <T> T select(final Token<K> token, final Supplier<T> current, final Supplier<T> stale) {
        return token.generation == token.state.generation ? current.get() : stale.get();
    }

    /** 提交显式本地变更，同时隔离操作在途期间发起的读取。 */
    synchronized <T> T commit(final Token<K> token, final Supplier<T> action) {
        token.state.generation++;
        return action.get();
    }

    /** 显式变更立即隔离旧读取，并排在此前同键写入之后；取消观察句柄不撤销已接受的 IO。 */
    <T> CompletionStage<T> mutate(final K key, final Function<Token<K>, CompletionStage<T>> action) {
        Token<K> token;
        CompletableFuture<Void> before;
        CompletableFuture<Void> done = new CompletableFuture<>();
        synchronized (this) {
            State state = states.get(key);
            if (state == null) state = new State();
            reserveWrite(state);
            states.put(key, state);
            state.references++;
            token = new Token<>(key, state, ++state.generation);
            before = state.tail;
            state.tail = done;
        }
        CompletionStage<T> execution = execute(before, done, token, () -> action.apply(token));
        return execution.toCompletableFuture().copy();
    }

    /** 加载回填仅在原代际有效时进入同键写序列；超时取消观察不能提前释放物理写入屏障。 */
    <T> CompletionStage<T> backfill(final Token<K> token, final Supplier<CompletionStage<T>> action,
            final Supplier<CompletionStage<T>> stale) {
        CompletableFuture<Void> before;
        CompletableFuture<Void> done = new CompletableFuture<>();
        synchronized (this) {
            if (token.generation != token.state.generation) return stale.get();
            reserveWrite(token.state);
            token.state.references++;
            before = token.state.tail;
            token.state.tail = done;
        }
        CompletionStage<T> execution = execute(before, done, token, () -> {
            boolean current;
            synchronized (this) { current = token.generation == token.state.generation; }
            return current ? action.get() : stale.get();
        });
        return execution.toCompletableFuture().copy();
    }

    private <T> CompletionStage<T> execute(final CompletableFuture<Void> before,
            final CompletableFuture<Void> done, final Token<K> token, final Supplier<CompletionStage<T>> action) {
        CompletableFuture<T> result = new CompletableFuture<>();
        AtomicBoolean settled = new AtomicBoolean();
        BiConsumer<T, Throwable> completion = (value, failure) -> {
            if (settled.compareAndSet(false, true)) finish(token, done, result, value, failure);
        };
        before.whenComplete((ignored, priorFailure) -> {
            try {
                CompletionStage<T> stage = action.get();
                stage.whenComplete(completion);
            } catch (RuntimeException | Error failure) {
                completion.accept(null, failure);
            }
        });
        return result;
    }

    /** 准入在短锁内完成，拒绝不创建状态引用或改变代际。 */
    private void reserveWrite(final State state) {
        if (state.writes >= MAX_WRITES_PER_KEY || writes >= MAX_WRITES) {
            throw ZeroException.of(CacheErrorCode.BACKPRESSURE_REJECTED, "cache pending write limit exceeded", null);
        }
        state.writes++;
        writes++;
    }

    /** 先回收准入与键引用，再推进屏障；用户观察回调在全部可运行屏障推进后、trampoline 外执行。 */
    private <T> void finish(final Token<K> token, final CompletableFuture<Void> done,
            final CompletableFuture<T> result, final T value, final Throwable failure) {
        CompletionDrain existing = completionDrain.get();
        CompletionDrain drain = existing == null ? new CompletionDrain() : existing;
        drain.barriers.addLast(() -> {
            synchronized (this) {
                token.state.writes--;
                writes--;
                release(token);
            }
            drain.observers.add(() -> {
                if (failure == null) result.complete(value);
                else result.completeExceptionally(failure);
            });
            done.complete(null);
        });
        if (existing != null) return;
        completionDrain.set(drain);
        try {
            while (!drain.barriers.isEmpty()) drain.barriers.removeFirst().run();
        } finally {
            completionDrain.remove();
        }
        for (Runnable observer : drain.observers) observer.run();
    }

    /** 一个在途读取/变更的身份；record 相等性包含键、状态实例和代际。 */
    record Token<K>(K key, State state, long generation) { }

    /** 单键状态，仅由外层短锁访问。 */
    private static final class State {
        /** 存活操作引用。 */
        private int references;
        /** 已接受且尚未物理结算的写入数量。 */
        private int writes;
        /** 显式变更代际。 */
        private long generation;
        /** 同键后端写入的完成屏障，无失败传播。 */
        private CompletableFuture<Void> tail = CompletableFuture.completedFuture(null);
    }

    /** 当前线程的完成推进上下文；不创建线程，也不长期保留 observer。 */
    private static final class CompletionDrain {
        /** 待推进的物理完成屏障。 */
        private final ArrayDeque<Runnable> barriers = new ArrayDeque<>();
        /** 屏障推进完成后才发布的用户观察结果。 */
        private final ArrayList<Runnable> observers = new ArrayList<>();
    }
}
