package group.zn.zero.examples.composition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import group.zn.zero.discovery.ServiceInstance;
import group.zn.zero.runtime.api.GameRuntime;
import group.zn.zero.runtime.assembly.RuntimeComposition;
import group.zn.zero.runtime.assembly.RuntimeProfile;
import group.zn.zero.runtime.discovery.DiscoveryRuntime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DiscoveryConsumerTest {
    @Test
    void localDiscoveryWorksWithoutNacos() {
        ServiceInstance instance = new ServiceInstance("game", "local", "127.0.0.1", 9000, true, Map.of());
        try (GameRuntime runtime = RuntimeComposition.builder(RuntimeProfile.local())
                .install(DiscoveryRuntime.module()).build()) {
            runtime.start();
            var discovery = runtime.require(DiscoveryRuntime.SERVICE_DISCOVERY);
            discovery.register(instance);
            assertEquals(List.of(instance), discovery.lookup("game"));
            discovery.unregister("game", "local");
            assertEquals(List.of(), discovery.lookup("game"));
        }
        assertThrows(ClassNotFoundException.class, () -> Class.forName("com.alibaba.nacos.api.NacosFactory"));
        assertThrows(ClassNotFoundException.class, () -> Class.forName("redis.clients.jedis.RedisClient"));
        System.out.println("modular-consumer=ok|profile=discovery");
    }
}
