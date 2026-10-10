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
    /** 测试拥有计时器，Adapter 仅拥有任务。 */
    @org.junit.jupiter.api.extension.RegisterExtension
    private final KafkaTestTimer timer = new KafkaTestTimer();
    /** 本地签名测试材料。 */
    private final SecurityMetadataAssertion assertion = SecurityMetadataAssertion.digest(java.util.Arrays.copyOf(new byte[] {1, 5}, 32));

    /** 缺 verifier 时，即使是结构正确的签名快照也不得进入业务。 */
    @Test
    void defaultReceiverFailsClosed() {
        HoldingGateway gateway = new HoldingGateway();
        try (KafkaRpcAdapter adapter = new KafkaRpcAdapter(KafkaRpcSettings.defaults("localhost:9092"), gateway, timer.executor())) {
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
        try (KafkaRpcAdapter adapter = new KafkaRpcAdapter(KafkaRpcSettings.defaults("localhost:9092"), gateway, timer.executor())) {
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

    /** producer 同步抛异常也必须保留与异步失败一致的观测。 */
    @Test
    void synchronousResponseSendFailureIsObserved() {
        HoldingGateway gateway = new HoldingGateway();
        gateway.sendFailure = new IllegalStateException("producer failed synchronously");
        AtomicInteger failures = new AtomicInteger();
        try (KafkaRpcAdapter adapter = new KafkaRpcAdapter(
                KafkaRpcSettings.defaults("localhost:9092"), gateway,
                event -> {
                    if (event.type() == group.zn.zero.rpc.observer.RpcTransportEventType.SEND_FAILED) {
                        failures.incrementAndGet();
                    }
                }, timer.executor())) {
            adapter.securityMetadataVerifier(SecurityMetadataAssertion.verifier(assertion));
            adapter.register("svc", "method", request -> CompletableFuture.completedFuture(
                    new RpcResponse("corr", "trace", SystemErrorCode.OK, "ok", new byte[0])));

            var processing = gateway.receive(request(metadata())).toCompletableFuture();

            assertTrue(processing.isCompletedExceptionally());
            assertEquals(1, failures.get());
        }
    }

    private SecurityMetadataSnapshot metadata() {
        Instant now = Instant.now();
        return SecurityMetadataAssertion.signed(new SecurityContext("subject", now, now.plusSeconds(60),
                "kafka", "peer", "trusted", "trace", "corr", Set.of("rpc"), Map.of()), "ref", assertion);
    }

    /** 过期毒消息无需再次发送不可达 reply；确认消费且不执行 handler。 */
    @Test void expiredRequestDoesNotRetryUnavailableReply() {
        HoldingGateway gateway = new HoldingGateway();
        try (KafkaRpcAdapter adapter = new KafkaRpcAdapter(KafkaRpcSettings.defaults("localhost:9092"),
                gateway, timer.executor())) {
            AtomicInteger calls = new AtomicInteger();
            adapter.register("svc", "method", request -> {
                calls.incrementAndGet();
                return CompletableFuture.completedFuture(null);
            });
            var expired = new RpcRequest("expired", "reply", "svc", "method", "trace", Instant.EPOCH,
                    RpcMode.REQUEST_RESPONSE, "", "", "", null, new byte[0]);
            gateway.receive(expired).toCompletableFuture().join();
            assertEquals(0, gateway.sends.get());
            assertEquals(0, calls.get());
        }
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
        /** 可选同步 producer 故障。 */
        private RuntimeException sendFailure;

        /** 返回尚未完成的 producer ack。 */
        @Override public CompletionStage<Void> send(final KafkaRpcMessage message) {
            sends.incrementAndGet();
            if (sendFailure != null) throw sendFailure;
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
