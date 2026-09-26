package group.zn.zero.cache;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 分层缓存的短期键状态：读取代际隔离、同键异步写顺序和引用计数回收。
 * 所有锁仅保护内存登记；外部操作及完成回调均在锁外触发。不创建线程。
 * @param <K> 缓存键类型。
 * @author zn
 */
final class CacheKeyOperations<K> {
    /** 只保存有在途操作的键，受本对象锁保护。 */
    private final Map<K, State> states = new HashMap<>();

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

    /** 显式变更立即隔离旧读取，并排在此前同键写入之后；外部 IO 在锁外执行。 */
    <T> CompletionStage<T> mutate(final K key, final Function<Token<K>, CompletionStage<T>> action) {
        Token<K> token;
        CompletableFuture<Void> before;
        CompletableFuture<Void> done = new CompletableFuture<>();
        synchronized (this) {
            State state = states.computeIfAbsent(key, ignored -> new State());
            state.references++;
            token = new Token<>(key, state, ++state.generation);
            before = state.tail;
            state.tail = done;
        }
        CompletionStage<T> execution = execute(before, done, () -> action.apply(token));
        execution.whenComplete((value, failure) -> release(token));
        return execution;
    }

    /** 加载回填仅在原代际有效时进入同键写序列，否则返回当前值而不写后端。 */
    <T> CompletionStage<T> backfill(final Token<K> token, final Supplier<CompletionStage<T>> action,
            final Supplier<CompletionStage<T>> stale) {
        CompletableFuture<Void> before;
        CompletableFuture<Void> done = new CompletableFuture<>();
        synchronized (this) {
            if (token.generation != token.state.generation) return stale.get();
            token.state.references++;
            before = token.state.tail;
            token.state.tail = done;
        }
        CompletionStage<T> execution = execute(before, done, () -> {
            boolean current;
            synchronized (this) { current = token.generation == token.state.generation; }
            return current ? action.get() : stale.get();
        });
        execution.whenComplete((value, failure) -> release(token));
        return execution;
    }

    private <T> CompletionStage<T> execute(final CompletableFuture<Void> before,
            final CompletableFuture<Void> done, final Supplier<CompletionStage<T>> action) {
        CompletableFuture<T> result = new CompletableFuture<>();
        java.util.concurrent.atomic.AtomicReference<CompletionStage<T>> actionStage =
                new java.util.concurrent.atomic.AtomicReference<>();
        before.whenComplete((ignored, priorFailure) -> {
            try {
                CompletionStage<T> stage = action.get();
                actionStage.set(stage);
                stage.whenComplete((value, failure) -> {
                    done.complete(null);
                    if (failure == null) result.complete(value);
                    else result.completeExceptionally(failure);
                });
            } catch (RuntimeException | Error failure) {
                done.complete(null);
                result.completeExceptionally(failure);
            }
        });
        result.whenComplete((ignored, failure) -> {
            if (result.isCancelled()) {
                CompletionStage<T> stage = actionStage.get();
                if (stage != null) {
                    stage.toCompletableFuture().cancel(true);
                }
            }
        });
        return result;
    }

    /** 一个在途读取/变更的身份；record 相等性包含键、状态实例和代际。 */
    record Token<K>(K key, State state, long generation) { }

    /** 单键状态，仅由外层短锁访问。 */
    private static final class State {
        /** 存活操作引用。 */
        private int references;
        /** 显式变更代际。 */
        private long generation;
        /** 同键后端写入的完成屏障，无失败传播。 */
        private CompletableFuture<Void> tail = CompletableFuture.completedFuture(null);
    }
}
