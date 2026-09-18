package group.zn.zero.examples.composition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import group.zn.zero.actor.ActorMessage;
import group.zn.zero.actor.handler.ActorHandler;
import group.zn.zero.actor.LaneKey;
import group.zn.zero.event.BasicZeroEvent;
import group.zn.zero.event.EventType;
import group.zn.zero.runtime.actor.ActorRuntime;
import group.zn.zero.runtime.api.GameRuntime;
import group.zn.zero.runtime.bootstrap.RuntimeBasics;
import group.zn.zero.runtime.event.EventRuntime;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class EventActorConsumerTest {
    @Test
    void publishesAnEventToAnActorWithoutTheStarterOrNetworking() {
        var planned = RuntimeBasics.builder().install(EventRuntime.module())
                .install(ActorRuntime.module()).diagnose();
        // 本组合的 REQUIRED 闭包恰为两条边：dead-letter -> event-bus，executors -> actor。
        var required = planned.edges().stream()
                .filter(edge -> edge.kind()
                        == group.zn.zero.runtime.diagnostics.RuntimeAssemblyPlan.DependencyEdgeKind.REQUIRED)
                .map(edge -> edge.from().value() + "->" + edge.to().value())
                .sorted()
                .toList();
        assertEquals(java.util.List.of(
                "zero.local.dead-letter->zero.local.event-bus",
                "zero.local.executors->zero.local.actor"), required);
        AtomicInteger handled = new AtomicInteger();
        try (GameRuntime runtime = RuntimeBasics.builder().install(EventRuntime.module())
                .install(ActorRuntime.module()).build()) {
            var actor = runtime.require(ActorRuntime.ACTOR_SCHEDULER);
            actor.register(String.class, ActorHandler.sync((context, message) -> handled.incrementAndGet()));
            var events = runtime.require(EventRuntime.EVENT_BUS);
            events.register(EventType.INTERNAL,
                    event -> actor.dispatch(new ActorMessage(LaneKey.player("example"), "payload")), 0);
            runtime.start();
            events.publish(new BasicZeroEvent("event", EventType.INTERNAL, "trace")).toCompletableFuture().join();
            assertEquals(1, handled.get());
        }
        for (String absent : new String[] {
                "group.zn.zero.starter.LocalRuntime", "io.netty.channel.Channel",
                "group.zn.zero.data.DataService", "redis.clients.jedis.RedisClient"}) {
            assertThrows(ClassNotFoundException.class, () -> Class.forName(absent));
        }
        System.out.println("modular-consumer=ok|profile=event-actor");
    }
}
