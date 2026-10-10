package group.zn.zero.examples.composition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.core.config.MapZeroConfig;
import group.zn.zero.runtime.capability.StandardRuntimeCapabilityModel;
import group.zn.zero.runtime.data.DataRuntime;
import group.zn.zero.runtime.production.ProductionAdapterException;
import group.zn.zero.runtime.production.ProductionAssembly;
import group.zn.zero.runtime.redis.RedisRuntime;
import group.zn.zero.runtime.bootstrap.ZeroRuntimeExecutors;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 独立 Redis 消费者的执行域及资源所有权回归。 @author zn */
class RedisConsumerTest {
    @Test
    void redisOnlyConsumerResolvesConfigurationAndOwnsItsClientWithoutOtherDrivers() {
        var executors = ZeroRuntimeExecutors.remoteIo("redis-consumer");
        try (executors) {
            var assembly = ProductionAssembly.builder("production", new MapZeroConfig(Map.of(
                    "zero.adapter.data.redis.enabled", "true", "zero.redis.uri", "redis://127.0.0.1:1")), executors)
                    .configSourceLookups(key -> null, key -> null).install(RedisRuntime.module());
            assertTrue(assembly.diagnose().missingConfigKeys().isEmpty());
            try (var runtime = assembly.build()) {
                assertEquals(1, runtime.requireAll(DataRuntime.DATA_SERVICES).size());
                assertTrue(runtime.plan().components().stream().anyMatch(component ->
                        component.componentId().equals(StandardRuntimeCapabilityModel.PRODUCTION_REDIS_DATA)));
                assertFalse(runtime.running());
            }
            assertThrows(java.util.concurrent.RejectedExecutionException.class,
                    () -> executors.remoteIoExecutor().execute(() -> { }));
        }
        for (String absent : new String[] {
                "org.apache.kafka.clients.producer.KafkaProducer", "com.mongodb.client.MongoClient",
                "org.postgresql.Driver", "com.alibaba.nacos.api.NacosFactory",
                "group.zn.zero.starter.LocalRuntime", "io.netty.channel.Channel"}) {
            assertThrows(ClassNotFoundException.class, () -> Class.forName(absent));
        }
        System.out.println("modular-consumer=ok|profile=redis");
    }

    /** 同步驱动不得在默认 direct IO 执行域运行。 */
    @Test void directIoIsRejected() {
        var assembly = ProductionAssembly.builder(new MapZeroConfig(Map.of(
                "zero.adapter.data.redis.enabled", "true", "zero.redis.uri", "redis://127.0.0.1:1")))
                .configSourceLookups(key -> null, key -> null).install(RedisRuntime.module());
        assertThrows(ProductionAdapterException.class, assembly::build);
    }

    /** 装配尚未接管执行器时的失败由调用方作用域回收。 */
    @Test void failedAssemblyStillClosesManagedIo() {
        var executors = ZeroRuntimeExecutors.remoteIo("redis-failure");
        try (executors) {
            var assembly = ProductionAssembly.builder("production",
                    new MapZeroConfig(Map.of("zero.adapter.data.redis.enabled", "true")), executors)
                    .configSourceLookups(key -> null, key -> null).install(RedisRuntime.module());
            assertThrows(ProductionAdapterException.class, assembly::build);
        }
        assertThrows(java.util.concurrent.RejectedExecutionException.class,
                () -> executors.remoteIoExecutor().execute(() -> { }));
    }

    @Test
    void enabledRedisReportsMissingConfigurationBeforeCreatingResources() {
        var assembly = ProductionAssembly.builder(new MapZeroConfig(Map.of("zero.adapter.data.redis.enabled", "true")))
                .configSourceLookups(key -> null, key -> null).install(RedisRuntime.module());
        assertEquals(java.util.List.of("zero.redis.uri"), assembly.diagnose().missingConfigKeys());
        assertThrows(ProductionAdapterException.class, assembly::build);
    }
}
