package group.zn.zero.examples.composition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.runtime.api.GameRuntime;
import group.zn.zero.runtime.bootstrap.RuntimeBasics;
import org.junit.jupiter.api.Test;

class MinimalConsumerTest {
    @Test
    void startsWithOnlyConfigurationAndExecutors() {
        try (GameRuntime runtime = RuntimeBasics.builder().build()) {
            assertEquals(2, runtime.plan().components().size());
            runtime.start();
            assertTrue(runtime.running());
            runtime.require(RuntimeBasics.EXECUTORS).logicExecutor().execute(() -> { });
        }
        for (String absent : new String[] {
                "group.zn.zero.actor.ActorMessage", "group.zn.zero.event.EventType",
                "group.zn.zero.log.LogAppender", "group.zn.zero.data.DataService",
                "redis.clients.jedis.RedisClient", "group.zn.zero.starter.LocalRuntime"}) {
            assertThrows(ClassNotFoundException.class, () -> Class.forName(absent));
        }
        System.out.println("modular-consumer=ok|profile=minimal");
    }
}
