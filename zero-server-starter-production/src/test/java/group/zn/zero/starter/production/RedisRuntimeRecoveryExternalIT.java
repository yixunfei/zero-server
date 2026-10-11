package group.zn.zero.starter.production;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.cache.CacheValueCodec;
import group.zn.zero.core.config.MapZeroConfig;
import group.zn.zero.core.error.ZeroException;
import group.zn.zero.data.mapping.ZeroDataField;
import group.zn.zero.data.mapping.ZeroDataId;
import group.zn.zero.data.mapping.ZeroDataObject;
import group.zn.zero.data.mapping.ZeroDataVersion;
import group.zn.zero.data.model.VersionedEntity;
import group.zn.zero.data.redis.RedisDistributedCacheService;
import group.zn.zero.data.redis.RedisDriverSettings;
import group.zn.zero.data.repository.CrudRepository;
import group.zn.zero.data.repository.RepositoryDefinition;
import group.zn.zero.data.repository.RepositorySource;
import group.zn.zero.protocol.buffer.ZeroReader;
import group.zn.zero.protocol.buffer.ZeroWriter;
import group.zn.zero.protocol.codec.ZeroPayloadCodec;
import group.zn.zero.runtime.cache.CacheRuntime;
import group.zn.zero.runtime.data.DataRuntime;
import group.zn.zero.runtime.production.ProductionAdapterErrorCode;
import group.zn.zero.runtime.production.ProductionAdapterNames;
import group.zn.zero.runtime.production.ZeroProductionAdapterState;
import group.zn.zero.runtime.production.ZeroProductionRuntime;
import group.zn.zero.runtime.production.ZeroProductionRuntimeConfigKeys;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** 真实 Redis 上的 data/cache 共享故障与恢复测试；只影响本测试的网络代理。 */
class RedisRuntimeRecoveryExternalIT {
    /** 真实驱动写入、读取、断连和两次恢复均经 Production runtime 执行。 */
    @Test
    void dataAndCacheShareDegradationAndRecoverAfterRealConnectionLoss() throws Exception {
        assertTrue(Boolean.getBoolean("zero.external.tests"));
        URI backend = URI.create(System.getProperty(RedisDriverSettings.PROPERTY_REDIS_URI,
                RedisDriverSettings.DEFAULT_URI));
        String suffix = UUID.randomUUID().toString().replace("-", "");
        try (RedisRecoveryFaultProxy proxy = new RedisRecoveryFaultProxy(backend.getHost(), backend.getPort());
                ZeroProductionRuntime runtime = runtime(proxy.port(), suffix)) {
            runtime.start();
            CrudRepository<String, RecoveryValue> repository = repository(runtime);
            @SuppressWarnings("unchecked")
            RedisDistributedCacheService<Object, Object> cache =
                    (RedisDistributedCacheService<Object, Object>) runtime.require(CacheRuntime.CACHE_SERVICE);
            String id = "id-" + suffix;
            repository.save(new RecoveryValue(id, 0, "before")).toCompletableFuture().get(3, TimeUnit.SECONDS);
            assertEquals("before", repository.findById(id).toCompletableFuture()
                    .get(3, TimeUnit.SECONDS).orElseThrow().value());
            cache.put(id, "cached").toCompletableFuture().get(3, TimeUnit.SECONDS);
            assertEquals(Optional.of("cached"), cache.get(id).toCompletableFuture().get(3, TimeUnit.SECONDS));
            assertEquals(Optional.empty(), cache.get("missing-" + suffix).toCompletableFuture()
                    .get(3, TimeUnit.SECONDS));
            assertStates(runtime, ZeroProductionAdapterState.HEALTHY);

            proxy.disconnect();
            cache.get("fault-" + suffix).toCompletableFuture().handle((value, failure) -> null)
                    .get(3, TimeUnit.SECONDS);
            awaitStates(runtime, ZeroProductionAdapterState.DEGRADED, Duration.ofSeconds(3));
            assertRejected(repository.findById(id).toCompletableFuture()::join);
            proxy.reconnect();
            awaitStates(runtime, ZeroProductionAdapterState.HEALTHY, Duration.ofSeconds(8));
            assertEquals("before", repository.findById(id).toCompletableFuture()
                    .get(3, TimeUnit.SECONDS).orElseThrow().value());

            proxy.disconnect();
            assertCode(ProductionAdapterErrorCode.WRITE_OUTCOME_UNKNOWN,
                    repository.save(new RecoveryValue(id, 1, "after")).toCompletableFuture()::join);
            awaitStates(runtime, ZeroProductionAdapterState.DEGRADED, Duration.ofSeconds(3));
            assertRejected(cache.get("other-" + suffix).toCompletableFuture()::join);
            proxy.reconnect();
            awaitStates(runtime, ZeroProductionAdapterState.HEALTHY, Duration.ofSeconds(8));
            assertEquals("before", repository.findById(id).toCompletableFuture()
                    .get(3, TimeUnit.SECONDS).orElseThrow().value());

            proxy.blackhole();
            try {
                repository.findById(id).toCompletableFuture().get(5, TimeUnit.SECONDS);
                throw new AssertionError("expected Redis socket timeout");
            } catch (java.util.concurrent.ExecutionException failure) {
                assertEquals(ProductionAdapterErrorCode.REMOTE_IO_FAILED,
                        ((ZeroException) failure.getCause()).errorCode());
            }
            awaitStates(runtime, ZeroProductionAdapterState.DEGRADED, Duration.ofSeconds(3));
            proxy.reconnect();
            awaitStates(runtime, ZeroProductionAdapterState.HEALTHY, Duration.ofSeconds(8));
            assertEquals("before", repository.findById(id).toCompletableFuture()
                    .get(3, TimeUnit.SECONDS).orElseThrow().value());
            cache.put(id, "recovered").toCompletableFuture().get(3, TimeUnit.SECONDS);
            cache.invalidate(id).toCompletableFuture().get(3, TimeUnit.SECONDS);
            assertEquals(Optional.empty(), cache.get(id).toCompletableFuture().get(3, TimeUnit.SECONDS));
            repository.deleteById(id).toCompletableFuture().get(3, TimeUnit.SECONDS);
        }
    }

    private ZeroProductionRuntime runtime(final int proxyPort, final String suffix) {
        return group.zn.zero.runtime.production.ProductionAssembly.builder("external-test", new MapZeroConfig(Map.of(
                ZeroProductionRuntimeConfigKeys.ADAPTER_DATA_REDIS_ENABLED, "true",
                ZeroProductionRuntimeConfigKeys.ADAPTER_CACHE_REDIS_ENABLED, "true",
                ZeroProductionRuntimeConfigKeys.ADAPTER_STARTUP_TIMEOUT_MILLIS, "1000",
                "zero.adapter.redis.operation-timeout-millis", "1000",
                "zero.adapter.redis.max-in-flight", "16",
                RedisDriverSettings.PROPERTY_REDIS_URI, "redis://127.0.0.1:" + proxyPort + "/0",
                ZeroProductionRuntimeConfigKeys.ADAPTER_CACHE_REDIS_NAMESPACE, "recovery-" + suffix,
                ZeroProductionRuntimeConfigKeys.ADAPTER_CACHE_REDIS_CACHE_NAME, "test")))
                .install(group.zn.zero.runtime.redis.RedisRuntime.module(new TextCodec())).build();
    }

    private CrudRepository<String, RecoveryValue> repository(final ZeroProductionRuntime runtime) {
        RepositorySource source = runtime.requireAll(DataRuntime.REPOSITORY_SOURCES).stream()
                .filter(value -> value.name().equals("redis")).findFirst().orElseThrow();
        return source.factory().create(RepositoryDefinition.of("recovery", String.class,
                RecoveryValue.class, new RecoveryCodec(), 1));
    }

    private void assertStates(final ZeroProductionRuntime runtime, final ZeroProductionAdapterState expected) {
        var readiness = runtime.healthSnapshot().components().stream()
                .filter(item -> item.phase() == group.zn.zero.runtime.health.HealthPhase.READINESS).toList();
        assertEquals(2, readiness.size());
        var health = expected == ZeroProductionAdapterState.HEALTHY
                ? group.zn.zero.runtime.health.HealthStatus.HEALTHY : group.zn.zero.runtime.health.HealthStatus.UNHEALTHY;
        readiness.forEach(item -> assertEquals(health, item.result().status()));
        var status = runtime.productionReport().adapterStatus(ProductionAdapterNames.ADAPTER_REDIS_DATA).orElseThrow();
        assertEquals(16, status.runtime().orElseThrow().maxInFlight());
        assertEquals(Duration.ofSeconds(1), status.settings().orElseThrow().operationTimeout());
        if (expected == ZeroProductionAdapterState.DEGRADED) {
            assertTrue(!status.runtime().orElseThrow().healthCode().isBlank());
        }
        assertEquals(expected, runtime.productionReport()
                .adapterStatus(ProductionAdapterNames.ADAPTER_REDIS_DATA).orElseThrow().state());
        assertEquals(expected, runtime.productionReport()
                .adapterStatus(ProductionAdapterNames.ADAPTER_REDIS_CACHE).orElseThrow().state());
    }

    private void awaitStates(final ZeroProductionRuntime runtime, final ZeroProductionAdapterState expected,
            final Duration timeout) throws InterruptedException {
        long until = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < until) {
            var statuses = runtime.productionReport().adapterStatuses();
            if (statuses.get(ProductionAdapterNames.ADAPTER_REDIS_DATA).state() == expected
                    && statuses.get(ProductionAdapterNames.ADAPTER_REDIS_CACHE).state() == expected) {
                assertStates(runtime, expected);
                return;
            }
            Thread.sleep(20);
        }
        assertStates(runtime, expected);
    }

    private void assertRejected(final Runnable operation) {
        assertCode(ProductionAdapterErrorCode.OPERATION_REJECTED, operation);
    }

    private void assertCode(final ProductionAdapterErrorCode expected, final Runnable operation) {
        try {
            operation.run();
            throw new AssertionError("expected " + expected);
        } catch (CompletionException failure) {
            assertNotNull(failure.getCause());
            assertTrue(failure.getCause() instanceof ZeroException);
            assertEquals(expected, ((ZeroException) failure.getCause()).errorCode());
        }
    }

    /** 单实体测试数据，隔离于其他 Redis namespace。 */
    @ZeroDataObject(namespace = "runtime-recovery", collection = "values", schemaVersion = 1)
    private record RecoveryValue(@ZeroDataId String id, @ZeroDataVersion long version,
            @ZeroDataField(order = 1) String value) implements VersionedEntity<String> {
        @Override public RecoveryValue withVersion(final long next) { return new RecoveryValue(id, next, value); }
    }

    /** 测试实体的稳定 wire 编码。 */
    private static final class RecoveryCodec implements ZeroPayloadCodec<RecoveryValue> {
        @Override public String name() { return "recovery"; }
        @Override public Class<RecoveryValue> messageType() { return RecoveryValue.class; }
        @Override public void write(final ZeroWriter writer, final RecoveryValue value) {
            writer.writeString(value.id()); writer.writeLong(value.version()); writer.writeString(value.value());
        }
        @Override public RecoveryValue read(final ZeroReader reader) {
            return new RecoveryValue(reader.readString(), reader.readLong(), reader.readString());
        }
    }

    /** 测试缓存文本编码。 */
    private static final class TextCodec implements CacheValueCodec<Object> {
        @Override public String name() { return "recovery-text"; }
        @Override public byte[] encode(final Object value) {
            return value.toString().getBytes(StandardCharsets.UTF_8);
        }
        @Override public Object decode(final byte[] value) {
            return new String(value, StandardCharsets.UTF_8);
        }
    }
}
