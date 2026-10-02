package group.zn.zero.starter.production;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.cache.CacheValueCodec;
import group.zn.zero.core.config.MapZeroConfig;
import group.zn.zero.data.redis.DefaultRedisDataKeyStrategy;
import group.zn.zero.data.redis.RedisCacheStore;
import group.zn.zero.data.redis.RedisDataAdapter;
import group.zn.zero.data.redis.RedisDistributedCacheService;
import group.zn.zero.data.redis.RedisDriverSettings;
import group.zn.zero.runtime.bootstrap.RuntimeBasics;
import group.zn.zero.runtime.cache.CacheRuntime;
import group.zn.zero.runtime.production.ZeroProductionRuntimeConfigKeys;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/** 真实 Redis 生产装配必须自动补写，并由运行时关闭维护 timer。 @author zn */
class RedisCacheMaintenanceExternalIT {
    /** 一次写入失败进入队列，恢复后无需手动 retry 即可到达 L2。 */
    @Test void providerRetriesAndRuntimeClosesTimer() throws Exception {
        String namespace = "audit" + UUID.randomUUID().toString().replace("-", "");
        var settings = RedisDriverSettings.fromSystemProperties();
        var codec = new RecoverableCodec();
        var runtime = ZeroProductionRuntimeFactory.externalTestBuilder(new MapZeroConfig(Map.of(
                ZeroProductionRuntimeConfigKeys.ADAPTER_CACHE_REDIS_ENABLED, "true",
                RedisDriverSettings.PROPERTY_REDIS_URI, settings.uri(),
                ZeroProductionRuntimeConfigKeys.ADAPTER_CACHE_REDIS_NAMESPACE, namespace,
                ZeroProductionRuntimeConfigKeys.ADAPTER_CACHE_REDIS_CACHE_NAME, "maintenance")))
                .executors(group.zn.zero.runtime.bootstrap.ZeroRuntimeExecutors.localPrototype("cache-maintenance", 1))
                .redisCacheValueCodec(codec).build();
        var timer = runtime.require(RuntimeBasics.EXECUTORS).scheduledExecutor("redis-cache-writeback");
        try (var client = new RedisDataAdapter(new DefaultRedisDataKeyStrategy()).createClient(settings)) {
            var reader = new RedisCacheStore<Object, Object>(client, namespace, "maintenance", codec);
            try {
                runtime.start();
                @SuppressWarnings("unchecked")
                var cache = (RedisDistributedCacheService<Object, Object>) runtime.require(CacheRuntime.CACHE_SERVICE);
                assertEquals(Optional.of("value"), cache.getOrLoad("key",
                        ignored -> CompletableFuture.completedFuture(Optional.of("value")))
                        .toCompletableFuture().get(2, TimeUnit.SECONDS));
                assertEquals(1, cache.backlogCount());
                codec.unavailable.set(false);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (cache.backlogCount() != 0 && System.nanoTime() < deadline) Thread.sleep(10);
                assertEquals(0, cache.backlogCount());
                assertEquals("value", reader.get("key").toCompletableFuture().get(2, TimeUnit.SECONDS).orElseThrow().value());
            } finally {
                runtime.close();
                client.del(reader.valueKey("key"), reader.versionKey("key"));
            }
        } finally {
            runtime.close();
        }
        assertTrue(timer.isShutdown());
    }

    /** 只注入本次写入故障，不停止共享 Redis 或修改服务配置。 */
    private static final class RecoverableCodec implements CacheValueCodec<Object> {
        /** 恢复开关；跨维护线程可见。 */
        private final AtomicBoolean unavailable = new AtomicBoolean(true);
        /** @return 稳定 codec 标识。 */
        @Override public String name() { return "audit-cache"; }
        /** 故障期间编码失败；恢复后编码正常。 */
        @Override public byte[] encode(final Object value) {
            if (unavailable.get()) throw new IllegalStateException("injected cache write failure");
            return value.toString().getBytes(StandardCharsets.UTF_8);
        }
        /** @return UTF-8 文本。 */
        @Override public Object decode(final byte[] value) { return new String(value, StandardCharsets.UTF_8); }
    }
}
