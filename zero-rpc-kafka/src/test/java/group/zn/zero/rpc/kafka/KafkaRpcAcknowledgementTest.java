package group.zn.zero.rpc.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.core.error.SystemErrorCode;
import group.zn.zero.rpc.RpcMode;
import group.zn.zero.rpc.RpcRequest;
import group.zn.zero.rpc.RpcResponse;
import group.zn.zero.security.SecurityContext;
import group.zn.zero.security.SecurityMetadataAssertion;
import group.zn.zero.security.SecurityMetadataSnapshot;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** RPC 确认必须等待业务和响应发送，默认接收策略不可绕过验证。 @author zn */
class KafkaRpcAcknowledgementTest {
    /** 本地签名测试材料。 */
    private final SecurityMetadataAssertion assertion = SecurityMetadataAssertion.digest(new byte[] {1, 5});

    /** 缺 verifier 时，即使是结构正确的签名快照也不得进入业务。 */
    @Test
    void defaultReceiverFailsClosed() {
        HoldingGateway gateway = new HoldingGateway();
        try (KafkaRpcAdapter adapter = new KafkaRpcAdapter(KafkaRpcSettings.defaults("localhost:9092"), gateway)) {
            AtomicInteger calls = new AtomicInteger();
            adapter.register("svc", "method", request -> {
                calls.incrementAndGet();
                return CompletableFuture.completedFuture(null);
            });
            CompletionStage<Void> processing = gateway.receive(request(metadata()));
            assertEquals(0, calls.get());
            assertFalse(processing.toCompletableFuture().isDone());
            gateway.sent.complete(null);
            processing.toCompletableFuture().join();
            gateway.receive(request(null)).toCompletableFuture().join();
            assertEquals(0, calls.get());
        }
    }

    /** handler 成功后仍须等待 producer ack，响应发送失败必须传播给 consumer。 */
    @Test
    void processingCompletesOnlyAfterResponseAcknowledgement() {
        HoldingGateway gateway = new HoldingGateway();
        try (KafkaRpcAdapter adapter = new KafkaRpcAdapter(KafkaRpcSettings.defaults("localhost:9092"), gateway)) {
            adapter.securityMetadataVerifier(SecurityMetadataAssertion.verifier(assertion));
            CompletableFuture<RpcResponse> handler = new CompletableFuture<>();
            adapter.register("svc", "method", request -> handler);
            var processing = gateway.receive(request(metadata())).toCompletableFuture();
            assertFalse(processing.isDone());
            handler.complete(new RpcResponse("corr", "trace", SystemErrorCode.OK, "ok", new byte[0]));
            assertFalse(processing.isDone());
            gateway.sent.completeExceptionally(new IllegalStateException("producer failed"));
            assertTrue(processing.isCompletedExceptionally());
            assertEquals(1, gateway.sends.get(), "send failure must not be converted into a second security response");
        }
    }

    private SecurityMetadataSnapshot metadata() {
        Instant now = Instant.now();
        return SecurityMetadataAssertion.signed(new SecurityContext("subject", now, now.plusSeconds(60),
                "kafka", "peer", "trusted", "trace", "corr", Set.of("rpc"), Map.of()), "ref", assertion);
    }

    private RpcRequest request(final SecurityMetadataSnapshot metadata) {
        return new RpcRequest("corr", "reply", "svc", "method", "trace", Instant.now().plusSeconds(60),
                RpcMode.REQUEST_RESPONSE, "", "", "", metadata, new byte[0]);
    }

    /** 仅手动完成 producer ack 的测试网关。 */
    private static final class HoldingGateway implements KafkaRpcMessageGateway {
        /** 唯一 request listener；reply 订阅不参与直接投递。 */
        private KafkaRpcMessageListener requestListener;
        /** producer 完成阶段。 */
        private final CompletableFuture<Void> sent = new CompletableFuture<>();
        /** 发送尝试次数。 */
        private final AtomicInteger sends = new AtomicInteger();

        /** 返回尚未完成的 producer ack。 */
        @Override public CompletionStage<Void> send(final KafkaRpcMessage message) {
            sends.incrementAndGet();
            return sent;
        }
        /** 测试构造期只使用带 group 的订阅。 */
        @Override public void subscribe(final String topic, final KafkaRpcMessageListener listener) { }
        /** request topic 命名含 request 标记。 */
        @Override public void subscribe(final String topic, final String group, final KafkaRpcMessageListener listener) {
            if (!topic.contains("reply")) requestListener = listener;
        }
        /** 测试网关无底层订阅资源。 */
        @Override public void unsubscribe(final String topic, final KafkaRpcMessageListener listener) { }
        /** 测试网关无底层资源。 */
        @Override public void close() { }

        private CompletionStage<Void> receive(final RpcRequest request) {
            return requestListener.onMessageAsync(new KafkaRpcMessage("request", "key",
                    new KafkaRpcEnvelopeCodec().encodeRequest(request)));
        }
    }
}
