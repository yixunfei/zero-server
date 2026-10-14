package group.zn.zero.game;

import group.zn.zero.core.error.SystemErrorCode;
import group.zn.zero.core.error.ZeroException;
import group.zn.zero.rpc.RpcCallContext;
import group.zn.zero.security.SecurityContext;
import group.zn.zero.security.SecurityContextBridge;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * 已认证请求的本地执行范围；只捕获验证后的身份，不携带密钥。
 * 框架 Actor 入口和交付回调显式恢复 scope，不承诺业务自行创建的异步链隐式传播。
 * 取消/期限仅阻止新执行与响应交付，不撤销已经发出的物理写入。
 * @author zn
 */
public final class RequestExecution {
    /** 当前线程范围。 */
    private static final ThreadLocal<RequestExecution> CURRENT = new ThreadLocal<>();
    /** 验证后的身份，保留原到期保护。 */
    private final SecurityContext identity;
    /** RPC 选项，保留响应 topic 及已有配置。 */
    private final RpcCallContext rpc;
    /** 本地单调期限起点。 */
    private final long started;
    /** 可测试的单调时钟，生产默认 nanoTime。 */
    private final java.util.function.LongSupplier ticker;
    /** 本地最大执行时长。 */
    private final long budgetNanos;
    /** 取消标记；与业务物理任务独立。 */
    private final AtomicBoolean cancelled = new AtomicBoolean();

    private RequestExecution(final SecurityContext identity, final Duration timeout) {
        this(identity, timeout, System::nanoTime);
    }

    RequestExecution(final SecurityContext identity, final Duration timeout, final java.util.function.LongSupplier ticker) {
        this.identity = Objects.requireNonNull(identity);
        this.ticker = Objects.requireNonNull(ticker);
        this.started = ticker.getAsLong();
        if (timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("timeout must be positive");
        budgetNanos = timeout.toNanos();
        rpc = RpcCallContext.current().withTraceId(identity.traceId());
        checkActive();
    }

    /** 从已认证入口捕获；必须在 SecurityContextBridge 范围内调用。
     * @param timeout 请求预算。 @return 新范围；线程安全。
     * @throws ZeroException 缺失或已过期身份。 @throws IllegalArgumentException 预算非法。 */
    public static RequestExecution capture(final Duration timeout) {
        return new RequestExecution(SecurityContextBridge.current().orElseThrow(
                () -> rejected("verified identity is required")), Objects.requireNonNull(timeout, "timeout"));
    }

    /** @return 当前范围；只读、线程安全，可能为空。 */
    public static Optional<RequestExecution> current() { return Optional.ofNullable(CURRENT.get()); }
    /** @return 可信身份；不可变，线程安全。 */
    public SecurityContext identity() { return identity; }
    /** 取消后不再准入或交付；线程安全，不取消物理保存。 */
    public void cancel() { cancelled.set(true); }

    /** 校验身份、期限和取消状态；线程安全。
     * @throws ZeroException 请求不可继续执行或交付。 */
    public void checkActive() {
        if (cancelled.get()) throw rejected("request cancelled");
        if (ticker.getAsLong() - started >= budgetNanos) throw rejected("request deadline exceeded");
        if (identity.expired(Instant.now())) throw rejected("verified identity expired");
    }

    /** 在可信 scope 内发起动作，结束后恢复线程状态；不会持有 ThreadLocal 跨越异步等待。
     * @param action 动作。 @param <T> 返回类型。 @return 动作结果。
     * @throws RuntimeException 范围失效或动作失败。 */
    public <T> T call(final Supplier<T> action) {
        checkActive();
        RequestExecution previous = CURRENT.get();
        CURRENT.set(this);
        long remainingMillis = Math.max(1, Duration.ofNanos(budgetNanos - (ticker.getAsLong() - started)).toMillis());
        long rpcMillis = Math.min(remainingMillis,
                rpc.resolveTimeoutMillis(group.zn.zero.rpc.RpcCallOptions.defaults(), remainingMillis));
        try {
            return SecurityContextBridge.with(identity, () -> RpcCallContext.with(
                    rpc.withTimeoutMillis(rpcMillis), action));
        } finally { restore(previous); }
    }

    /** 交付阶段在实际完成后重新验证并恢复 scope；取消返回 future 不释放物理任务的 Actor lane。
     * @param physical 实际业务阶段。 @param <T> 结果类型。 @return 独立交付 future；线程安全。
     * @throws NullPointerException 阶段为空。 */
    public <T> CompletableFuture<T> deliver(final CompletionStage<T> physical) {
        var delivery = new CompletableFuture<T>();
        delivery.whenComplete((value, failure) -> { if (delivery.isCancelled()) cancel(); });
        physical.whenComplete((value, failure) -> {
            try {
                call(() -> {
                    if (failure == null) delivery.complete(value); else delivery.completeExceptionally(failure);
                    return null;
                });
            } catch (RuntimeException rejected) { delivery.completeExceptionally(rejected); }
        });
        return delivery;
    }

    /** 框架后台动作的空范围，避免直接调度嵌套执行时继承无关玩家身份。
     * @param action 动作。 @param <T> 返回类型。 @return 动作结果；线程安全。
     * @throws RuntimeException 动作失败。 */
    public static <T> T without(final Supplier<T> action) {
        RequestExecution previous = CURRENT.get();
        CURRENT.remove();
        try { return SecurityContextBridge.without(() -> RpcCallContext.with(RpcCallContext.empty(), action)); }
        finally { restore(previous); }
    }

    private static void restore(final RequestExecution previous) {
        if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
    }

    private static ZeroException rejected(final String message) {
        return ZeroException.of(SystemErrorCode.INVALID_ARGUMENT, message, null);
    }
}
