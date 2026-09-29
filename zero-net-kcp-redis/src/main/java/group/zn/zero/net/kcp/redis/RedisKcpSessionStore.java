package group.zn.zero.net.kcp.redis;

import group.zn.zero.net.kcp.KcpSessionOwner;
import group.zn.zero.net.kcp.KcpSessionServices;
import group.zn.zero.net.kcp.KcpSessionSnapshot;
import group.zn.zero.net.kcp.KcpSessionSnapshotCodec;
import group.zn.zero.net.kcp.KcpSessionStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import redis.clients.jedis.RedisClient;

/**
 * Redis 单 key 原子所有权状态机；用 Redis TIME 判定租约，保留 24h 代际墓碑。
 * 调用方提供有界、非内联远程 IO 执行器并拥有 client/executor 生命周期；网络故障 fail-closed。
 * 不支持从丢失墓碑的 Redis 备份继续旧票据；恢复后必须撤销旧票据并重新登录。
 * @author zn
 */
public final class RedisKcpSessionStore implements KcpSessionStore {
    /** 共享不可变脚本，不拼接任何用户输入。 */
    private static final String SCRIPT = script();
    /** 调用方拥有的线程安全连接池客户端。 */
    private final RedisClient client;
    /** 隔离 key 空间。 */
    private final String prefix;
    /** 调用方受管远程 IO 执行器。 */
    private final Executor executor;
    /** 包含已排队任务的硬上限。 */
    private final Semaphore pending;

    /**
     * 创建独立命名空间适配器；线程安全，无网络副作用。
     * @param client 有有限连接/读取超时的池化客户端，由调用方关闭。
     * @param prefix 稳定命名空间，只允许字母数字、点、下划线和短横线。
     * @param executor 非内联远程 IO 执行器，由框架统一管理。
     * @param maxPending 执行及排队上限，1..65536；超限以异常完成。
     * @throws IllegalArgumentException 参数非法。
     */
    public RedisKcpSessionStore(final RedisClient client, final String prefix, final Executor executor, final int maxPending) {
        KcpSessionOwner.requireIdentity(1, prefix, 1);
        if (maxPending < 1 || maxPending > 65536) throw new IllegalArgumentException("invalid Redis pending limit");
        this.client = Objects.requireNonNull(client); this.prefix = prefix;
        this.executor = Objects.requireNonNull(executor); pending = new Semaphore(maxPending);
    }
    /** {@inheritDoc} */
    @Override public CompletionStage<Boolean> acquire(final int conv, final String owner, final long generation, final Duration lease) {
        return change("acquire", conv, owner, generation, lease);
    }
    /** {@inheritDoc} */
    @Override public CompletionStage<Boolean> renew(final int conv, final String owner, final long generation, final Duration lease) {
        return change("renew", conv, owner, generation, lease);
    }
    /** {@inheritDoc} */
    @Override public CompletionStage<Boolean> release(final int conv, final String owner, final long generation) {
        return change("release", conv, owner, generation, Duration.ofSeconds(1));
    }
    /** {@inheritDoc} */
    @Override public CompletionStage<Boolean> freeze(final int conv, final String owner, final long generation) {
        return change("freeze", conv, owner, generation, Duration.ofSeconds(1));
    }
    /** {@inheritDoc} */
    @Override public CompletionStage<Boolean> unfreeze(final int conv, final String owner, final long generation) {
        return change("unfreeze", conv, owner, generation, Duration.ofSeconds(1));
    }
    /** {@inheritDoc} */
    @Override public CompletionStage<Boolean> claim(final int conv, final String owner, final long generation, final Duration lease) {
        return change("claim", conv, owner, generation, lease);
    }
    /** {@inheritDoc} */
    @Override public CompletionStage<Boolean> migrate(final KcpSessionSnapshot snapshot, final String targetOwner,
            final long targetGeneration, final Duration lease) {
        Objects.requireNonNull(snapshot); validate(snapshot.conv(), targetOwner, targetGeneration, lease);
        if (targetGeneration <= snapshot.generation() || targetOwner.equals(snapshot.owner())) return CompletableFuture.completedFuture(false);
        String encoded = Base64.getEncoder().encodeToString(KcpSessionSnapshotCodec.encode(snapshot));
        var args = List.of("migrate", snapshot.owner(), Long.toString(snapshot.generation()), Long.toString(lease.toMillis()),
                targetOwner, Long.toString(targetGeneration), encoded, Long.toString(snapshot.expiresAt().toEpochMilli()));
        return submit(() -> success(eval(snapshot.conv(), args)));
    }
    /** {@inheritDoc} */
    @Override public CompletionStage<KcpSessionOwner> owner(final int conv) {
        requireConv(conv);
        return submit(() -> {
            Object result = eval(conv, List.of("owner"));
            if (!(result instanceof List<?> values) || values.isEmpty()) return null;
            return new KcpSessionOwner(conv, values.get(0).toString(), Long.parseLong(values.get(1).toString()),
                    Instant.ofEpochMilli(Long.parseLong(values.get(2).toString())), KcpSessionOwner.Phase.valueOf(values.get(3).toString()));
        });
    }
    /** {@inheritDoc} */
    @Override public CompletionStage<KcpSessionSnapshot> load(final int conv) {
        requireConv(conv);
        return submit(() -> {
            Object result = eval(conv, List.of("load"));
            return result == null || Boolean.FALSE.equals(result) || "false".equals(result.toString())
                    ? null : KcpSessionSnapshotCodec.decode(Base64.getDecoder().decode(text(result)));
        });
    }
    private CompletionStage<Boolean> change(final String op, final int conv, final String owner, final long generation, final Duration lease) {
        validate(conv, owner, generation, lease);
        return submit(() -> success(eval(conv, List.of(op, owner, Long.toString(generation), Long.toString(lease.toMillis())))));
    }
    private Object eval(final int conv, final List<String> args) {
        return client.eval(SCRIPT, List.of(prefix + ":{" + Integer.toUnsignedString(conv) + "}:owner"), args);
    }
    private <T> CompletionStage<T> submit(final Supplier<T> action) {
        if (!pending.tryAcquire()) return CompletableFuture.failedFuture(new java.util.concurrent.RejectedExecutionException("Redis KCP queue full"));
        var result = new CompletableFuture<T>(); Thread caller = Thread.currentThread();
        try {
            executor.execute(() -> {
                try {
                    if (Thread.currentThread() == caller) throw new IllegalStateException("Redis executor must not run inline");
                    result.complete(action.get());
                } catch (RuntimeException failure) { result.completeExceptionally(failure); }
                finally { pending.release(); }
            });
        } catch (RuntimeException failure) { pending.release(); result.completeExceptionally(failure); }
        return result.minimalCompletionStage();
    }
    private static boolean success(final Object result) { return result instanceof Number value && value.longValue() == 1; }
    private static String text(final Object value) {
        if (value instanceof byte[] bytes) return new String(bytes, StandardCharsets.UTF_8);
        return value.toString();
    }
    private static void validate(final int conv, final String owner, final long generation, final Duration lease) {
        KcpSessionOwner.requireIdentity(conv, owner, generation); KcpSessionServices.requireLease(lease);
    }
    private static void requireConv(final int conv) { if (conv == 0) throw new IllegalArgumentException("conv is zero"); }
    private static String script() {
        try (var stream = RedisKcpSessionStore.class.getResourceAsStream("/kcp-owner.lua")) {
            if (stream == null) throw new IllegalStateException("KCP Redis script missing");
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
}
