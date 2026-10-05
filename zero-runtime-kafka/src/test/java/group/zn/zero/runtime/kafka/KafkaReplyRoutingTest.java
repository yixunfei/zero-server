package group.zn.zero.runtime.kafka;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import group.zn.zero.rpc.RpcMode;
import group.zn.zero.rpc.RpcRequest;
import group.zn.zero.rpc.kafka.KafkaRpcSettings;
import group.zn.zero.rpc.observer.RpcTransportObserver;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** runtime reply topic 必须实际进入出站请求，而不是只改变配置快照。 @author zn */
class KafkaReplyRoutingTest {
    /** 仅替换 reply topic；保留业务和安全元数据，并允许已路由请求直接委托。 */
    @Test
    void requestUsesInstanceReplyTopicAndPreservesAllOtherFields() {
        var captured = new AtomicReference<RpcRequest>();
        var resource = (KafkaRpcAdapterResource) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {KafkaRpcAdapterResource.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "request", "oneway" -> {
                        captured.set((RpcRequest) args[0]);
                        yield CompletableFuture.completedFuture(null);
                    }
                    case "exposedAdapter" -> Optional.empty();
                    case "close" -> null;
                    default -> throw new AssertionError(method.getName());
                });
        var settings = KafkaRpcSettings.defaults("127.0.0.1:9092");
        var lifecycle = new KafkaRpcLifecycleAdapter(settings, RpcTransportObserver.noop(), (s, o) -> resource);
        lifecycle.start();
        try {
            var metadata = new group.zn.zero.security.SecurityMetadataSnapshot("subject", "rpc", "peer", "trusted",
                    "trace", "correlation", java.util.Set.of("rpc.invoke"), "assertion", Instant.EPOCH,
                    Instant.EPOCH.plusSeconds(10), "signed");
            var original = new RpcRequest("correlation", "shared.reply", "service", "method", "trace",
                    Instant.now().plusSeconds(3), RpcMode.REQUEST_RESPONSE, "request.topic", "request.group",
                    "partition", metadata, new byte[] {1, 2, 3});
            lifecycle.request(original).toCompletableFuture().join();
            var routed = captured.get();
            assertEquals(settings.replyTopic(), routed.replyTopic());
            assertEquals(original.correlationId(), routed.correlationId());
            assertEquals(original.serviceName(), routed.serviceName());
            assertEquals(original.methodName(), routed.methodName());
            assertEquals(original.traceId(), routed.traceId());
            assertEquals(original.timeoutAt(), routed.timeoutAt());
            assertEquals(original.mode(), routed.mode());
            assertEquals(original.topic(), routed.topic());
            assertEquals(original.group(), routed.group());
            assertEquals(original.partitionKey(), routed.partitionKey());
            assertSame(original.securityMetadata(), routed.securityMetadata());
            assertArrayEquals(original.payload(), routed.payload());
            lifecycle.request(routed).toCompletableFuture().join();
            assertSame(routed, captured.get());
            lifecycle.oneway(original).toCompletableFuture().join();
            assertSame(original, captured.get());
        } finally {
            lifecycle.close();
        }
    }
}
