package group.zn.zero.event.handler;

import group.zn.zero.event.ZeroEvent;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.Predicate;

/**
 * 显式重试单个处理器，最多 16 次；默认总线不重试业务异常。
 * 首次尝试在调用线程同步启动，后续重试使用调用方受管执行器；调用方必须保证处理器副作用幂等。
 * 不创建线程或阻塞等待；重试全部失败后由总线统一记死信。
 * @author zn
 */
public final class RetryingEventHandler implements EventHandler {
    /** 被装饰的业务处理器。 */
    private final EventHandler delegate;
    /** 包含首次调用的尝试次数。 */
    private final int attempts;
    /** 显式可重试故障策略。 */
    private final Predicate<Throwable> retryable;
    /** 生命周期由调用方管理的执行器。 */
    private final Executor executor;

    /** 创建有界重试；参数不可为空，attempts 为 1 至 16，执行器不得在 IO 线程内联业务。 */
    public RetryingEventHandler(final EventHandler delegate, final int attempts,
            final Predicate<Throwable> retryable, final Executor executor) {
        if (attempts < 1 || attempts > 16) throw new IllegalArgumentException("attempts must be between 1 and 16");
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.attempts = attempts;
        this.retryable = Objects.requireNonNull(retryable, "retryable");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    /** 按序尝试同一事件，成功立即结束；线程安全性取决于 delegate 和策略。 */
    @Override public CompletionStage<Void> handle(final ZeroEvent event) {
        Objects.requireNonNull(event, "event");
        CompletionStage<Void> result;
        result = invoke(event);
        for (int index = 1; index < attempts; index++) {
            result = result.handleAsync((ignored, failure) -> {
                if (failure == null) return CompletableFuture.<Void>completedFuture(null);
                Throwable cause = unwrap(failure);
                if (cause instanceof Error || !retryable.test(cause)) return CompletableFuture.<Void>failedFuture(cause);
                return invoke(event);
            }, executor).thenCompose(stage -> stage);
        }
        return result.toCompletableFuture().minimalCompletionStage();
    }

    private CompletionStage<Void> invoke(final ZeroEvent event) {
        try { return Objects.requireNonNull(delegate.handle(event), "handler stage"); }
        catch (RuntimeException | Error failure) { return CompletableFuture.failedFuture(failure); }
    }

    private Throwable unwrap(final Throwable failure) {
        Throwable current = failure;
        while (current instanceof CompletionException && current.getCause() != null) current = current.getCause();
        return current;
    }
}
