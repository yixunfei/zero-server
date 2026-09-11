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
import java.util.Map;
import org.junit.jupiter.api.Test;

class RedisConsumerTest {
    @Test
    void redisOnlyConsumerResolvesConfigurationAndOwnsItsClientWithoutOtherDrivers() {
        var assembly = ProductionAssembly.builder(new MapZeroConfig(Map.of(
                "zero.adapter.data.redis.enabled", "true", "zero.redis.uri", "redis://127.0.0.1:1")))
                .configSourceLookups(key -> null, key -> null).install(RedisRuntime.module());
        assertTrue(assembly.diagnose().missingConfigKeys().isEmpty());
        try (var runtime = assembly.build()) {
            assertEquals(1, runtime.requireAll(DataRuntime.DATA_SERVICES).size());
            assertTrue(runtime.plan().components().stream().anyMatch(component ->
                    component.componentId().equals(StandardRuntimeCapabilityModel.PRODUCTION_REDIS_DATA)));
            assertFalse(runtime.running());
        }
        for (String absent : new String[] {
                "org.apache.kafka.clients.producer.KafkaProducer", "com.mongodb.client.MongoClient",
                "org.postgresql.Driver", "com.alibaba.nacos.api.NacosFactory",
                "group.zn.zero.starter.LocalRuntime", "io.netty.channel.Channel"}) {
            assertThrows(ClassNotFoundException.class, () -> Class.forName(absent));
        }
        System.out.println("modular-consumer=ok|profile=redis");
    }

    @Test
    void enabledRedisReportsMissingConfigurationBeforeCreatingResources() {
        var assembly = ProductionAssembly.builder(new MapZeroConfig(Map.of("zero.adapter.data.redis.enabled", "true")))
                .configSourceLookups(key -> null, key -> null).install(RedisRuntime.module());
        assertEquals(java.util.List.of("zero.redis.uri"), assembly.diagnose().missingConfigKeys());
        assertThrows(ProductionAdapterException.class, assembly::build);
    }
}
