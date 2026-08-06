package group.zn.zero.rpc;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * RPC 单次调用上下文。
 *
 * <p>该上下文用于在不重新创建客户端代理的前提下，为当前调用线程覆盖 traceId、replyTopic
 * 和 timeoutMillis。上下文基于 ThreadLocal 保存，不会自动跨线程传播；跨线程发起 RPC 时应先捕获
 * 当前上下文，再在目标线程使用 {@link #with(RpcCallContext, Supplier)} 显式包裹调用。</p>
 *
 * @author zn
 */
public final class RpcCallContext {

    /**
     * 空上下文。
     */
    private static final RpcCallContext EMPTY = new RpcCallContext(null, null, 0L);

    /**
     * 当前线程上下文。
     */
    private static final ThreadLocal<RpcCallContext> CURRENT = new ThreadLocal<>();

    /**
     * 覆盖用 reply topic；为空表示使用客户端默认选项。
     */
    private final String replyTopic;

    /**
     * 覆盖用 traceId；为空表示使用客户端默认选项。
     */
    private final String traceId;

    /**
     * 覆盖用超时毫秒数；0 表示使用客户端默认选项或方法默认值。
     */
    private final long timeoutMillis;

    private RpcCallContext(final String replyTopic, final String traceId, final long timeoutMillis) {
        if (timeoutMillis < 0L) {
            throw new IllegalArgumentException("timeoutMillis must not be negative");
        }
        this.replyTopic = replyTopic;
        this.traceId = traceId;
        this.timeoutMillis = timeoutMillis;
    }

    /**
     * 返回空调用上下文。
     *
     * @return 空上下文；不可为空；线程安全。
     */
    public static RpcCallContext empty() {
        return EMPTY;
    }

    /**
     * 返回当前线程调用上下文。
     *
     * @return 当前线程上下文；未设置时返回空上下文；不可为空；线程安全。
     */
    public static RpcCallContext current() {
        RpcCallContext context = CURRENT.get();
        return context == null ? EMPTY : context;
    }

    /**
     * 从调用选项创建上下文。
     *
     * @param options 调用选项；不可为空。
     * @return 调用上下文；不可为空；线程安全。
     * @throws NullPointerException 当调用选项为空时抛出。
     */
    public static RpcCallContext from(final RpcCallOptions options) {
        RpcCallOptions current = Objects.requireNonNull(options, "options");
        return new RpcCallContext(current.replyTopic(), current.traceId(), current.timeoutMillis());
    }

    /**
     * 在指定上下文中执行回调，并在结束后恢复调用线程原上下文。
     *
     * @param context 本次调用上下文；不可为空。
     * @param supplier 回调；不可为空。
     * @param <T> 回调返回值类型。
     * @return 回调返回值；可为空；线程安全性由回调自身保证。
     * @throws NullPointerException 当上下文或回调为空时抛出。
     * @throws RuntimeException 当回调自身抛出运行时异常时向上透传。
     */
    public static <T> T with(final RpcCallContext context, final Supplier<T> supplier) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(supplier, "supplier");
        RpcCallContext previous = CURRENT.get();
        CURRENT.set(context);
        try {
            return supplier.get();
        } finally {
            restore(previous);
        }
    }

    /**
     * 在指定调用选项中执行回调，并在结束后恢复调用线程原上下文。
     *
     * @param options 本次调用选项；不可为空。
     * @param supplier 回调；不可为空。
     * @param <T> 回调返回值类型。
     * @return 回调返回值；可为空；线程安全性由回调自身保证。
     * @throws NullPointerException 当调用选项或回调为空时抛出。
     * @throws RuntimeException 当回调自身抛出运行时异常时向上透传。
     */
    public static <T> T with(final RpcCallOptions options, final Supplier<T> supplier) {
        return with(from(options), supplier);
    }

    /**
     * 在指定上下文中执行任务，并在结束后恢复调用线程原上下文。
     *
     * @param context 本次调用上下文；不可为空。
     * @param runnable 任务；不可为空。
     * @throws NullPointerException 当上下文或任务为空时抛出。
     * @throws RuntimeException 当任务自身抛出运行时异常时向上透传。
     */
    public static void with(final RpcCallContext context, final Runnable runnable) {
        Objects.requireNonNull(runnable, "runnable");
        with(context, () -> {
            runnable.run();
            return null;
        });
    }

    /**
     * 在指定调用选项中执行任务，并在结束后恢复调用线程原上下文。
     *
     * @param options 本次调用选项；不可为空。
     * @param runnable 任务；不可为空。
     * @throws NullPointerException 当调用选项或任务为空时抛出。
     * @throws RuntimeException 当任务自身抛出运行时异常时向上透传。
     */
    public static void with(final RpcCallOptions options, final Runnable runnable) {
        with(from(options), runnable);
    }

    /**
     * 返回替换 traceId 后的新上下文。
     *
     * @param value traceId；不可为空。
     * @return 新上下文；不可为空；线程安全。
     * @throws NullPointerException 当 traceId 为空时抛出。
     */
    public RpcCallContext withTraceId(final String value) {
        return new RpcCallContext(replyTopic, Objects.requireNonNull(value, "value"), timeoutMillis);
    }

    /**
     * 返回替换 reply topic 后的新上下文。
     *
     * @param value reply topic；不可为空。
     * @return 新上下文；不可为空；线程安全。
     * @throws NullPointerException 当 reply topic 为空时抛出。
     */
    public RpcCallContext withReplyTopic(final String value) {
        return new RpcCallContext(Objects.requireNonNull(value, "value"), traceId, timeoutMillis);
    }

    /**
     * 返回替换超时毫秒数后的新上下文。
     *
     * @param value 超时毫秒数；0 表示不覆盖默认选项。
     * @return 新上下文；不可为空；线程安全。
     * @throws IllegalArgumentException 当超时毫秒数为负数时抛出。
     */
    public RpcCallContext withTimeoutMillis(final long value) {
        return new RpcCallContext(replyTopic, traceId, value);
    }

    /**
     * 解析最终 reply topic。
     *
     * @param fallback 默认调用选项；不可为空。
     * @return reply topic；不可为空；线程安全。
     * @throws NullPointerException 当默认调用选项为空时抛出。
     */
    public String resolveReplyTopic(final RpcCallOptions fallback) {
        return replyTopic == null ? Objects.requireNonNull(fallback, "fallback").replyTopic() : replyTopic;
    }

    /**
     * 解析最终 traceId。
     *
     * @param fallback 默认调用选项；不可为空。
     * @return traceId；不可为空；线程安全。
     * @throws NullPointerException 当默认调用选项为空时抛出。
     */
    public String resolveTraceId(final RpcCallOptions fallback) {
        return traceId == null ? Objects.requireNonNull(fallback, "fallback").traceId() : traceId;
    }

    /**
     * 解析最终超时毫秒数。
     *
     * @param fallback 默认调用选项；不可为空。
     * @param defaultTimeoutMillis 方法默认超时毫秒数；必须大于 0。
     * @return 最终超时毫秒数；线程安全。
     * @throws NullPointerException 当默认调用选项为空时抛出。
     * @throws IllegalArgumentException 当方法默认超时毫秒数非法时抛出。
     */
    public long resolveTimeoutMillis(final RpcCallOptions fallback, final long defaultTimeoutMillis) {
        if (timeoutMillis > 0L) {
            return timeoutMillis;
        }
        return Objects.requireNonNull(fallback, "fallback").resolveTimeoutMillis(defaultTimeoutMillis);
    }

    private static void restore(final RpcCallContext previous) {
        if (previous == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(previous);
        }
    }
}
