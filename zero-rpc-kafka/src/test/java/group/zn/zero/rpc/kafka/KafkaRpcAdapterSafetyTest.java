package group.zn.zero.rpc.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import group.zn.zero.rpc.RpcMode;
import group.zn.zero.rpc.RpcRequest;
import group.zn.zero.rpc.observer.RpcTransportEventType;
import group.zn.zero.rpc.observer.RpcTransportObserver;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Kafka RPC adapter observer 与注销事务安全测试。
 *
 * @author zn
 */
class KafkaRpcAdapterSafetyTest {

    /** 测试使用的第三方敏感异常标记。 */
    private static final String FAILURE_SECRET = "adapter-observer-secret";

    /**
     * 验证普通业务发送事件的 observer {@link Error} 不改变 oneway 完成结果。
     */
    @Test
    void adapterObserverErrorShouldNotChangeBusinessCompletion() {
        SafetyGateway gateway = new SafetyGateway(false);
        AtomicInteger observerCalls = new AtomicInteger();
        RpcTransportObserver observer = event -> {
            if (event.type() == RpcTransportEventType.ONEWAY_SENT) {
                observerCalls.incrementAndGet();
                throw new AssertionError(FAILURE_SECRET + "-oneway");
            }
        };
        KafkaRpcAdapter adapter = new KafkaRpcAdapter(settings(), gateway, observer);

        adapter.oneway(request("observer-error", "service", "method")).toCompletableFuture().join();

        assertEquals(1, observerCalls.get());
        assertEquals(1L, adapter.snapshot().onewaySentCount());
        adapter.close();
        assertEquals(1, gateway.closeCount());
    }

    /**
     * 验证 request unsubscribe 失败不会提前删除 handler/route/refcount，后续重试仍真实取消订阅。
     */
    @Test
    void unregisterFailureShouldPreserveRegistrationForRetry() {
        SafetyGateway gateway = new SafetyGateway(true);
        KafkaRpcAdapter adapter = new KafkaRpcAdapter(settings(), gateway);
        AtomicInteger handlerCalls = new AtomicInteger();
        adapter.register("service", "method", "request-topic", "request-group", request -> {
            handlerCalls.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        });

        KafkaRpcResourceException failure = assertThrows(KafkaRpcResourceException.class,
                () -> adapter.unregister("service", "method"));

        assertEquals("unsubscribe kafka rpc request topic failed", failure.getMessage());
        assertNull(failure.getCause());
        assertEquals(0, failure.getSuppressed().length);
        assertFalse(failure.toString().contains(FAILURE_SECRET));
        assertEquals("1", adapter.snapshot().attributes().get("requestSubscriptionCount"));
        gateway.emitRequest(request("after-unregister-failure", "service", "method"));
        assertEquals(1, handlerCalls.get());

        adapter.unregister("service", "method");

        assertEquals(2, gateway.requestUnsubscribeCount());
        assertEquals(List.of(
                "request-topic#request-group",
                "request-topic#request-group"), gateway.requestUnsubscribeRoutes());
        assertEquals("0", adapter.snapshot().attributes().get("requestSubscriptionCount"));
        adapter.close();
        assertEquals(2, gateway.requestUnsubscribeCount());
        assertEquals(1, gateway.closeCount());
    }

    /**
     * 创建测试 adapter 配置。
     *
     * @return 非空、不可变、线程安全配置。
     */
    private KafkaRpcSettings settings() {
        return new KafkaRpcSettings(
                "localhost:9092",
                "adapter-safety-client",
                "adapter-safety-group",
                "adapter.safety",
                "adapter-safety-reply",
                8,
                Duration.ofMillis(10),
                Duration.ofSeconds(1),
                Map.of(),
                Map.of());
    }

    /**
     * 创建有效的 oneway 测试请求。
     *
     * @param correlationId 关联 ID；不可为空。
     * @param serviceName 服务名；不可为空。
     * @param methodName 方法名；不可为空。
     * @return 非空、线程安全请求。
     */
    private RpcRequest request(
            final String correlationId,
            final String serviceName,
            final String methodName) {
        return new RpcRequest(
                correlationId,
                "reply",
                serviceName,
                methodName,
                "trace",
                Instant.now().plusSeconds(30),
                RpcMode.ONEWAY,
                new byte[0]);
    }

    /**
     * 支持 request 注销故障注入与消息回放的测试网关。
     *
     * @author zn
     */
    private static final class SafetyGateway implements KafkaRpcMessageGateway {

        /** 首次 request unsubscribe 是否注入 Error。 */
        private final boolean failFirstRequestUnsubscribe;

        /** request unsubscribe 次数。 */
        private final AtomicInteger requestUnsubscribeCount = new AtomicInteger();

        /** request unsubscribe 使用的 topic/group 有序记录。 */
        private final List<String> requestUnsubscribeRoutes = new CopyOnWriteArrayList<>();

        /** close 次数。 */
        private final AtomicInteger closeCount = new AtomicInteger();

        /** 当前 request 消息监听器。 */
        private volatile KafkaRpcMessageListener requestListener;

        /**
         * 创建测试网关。
         *
         * @param failFirstRequestUnsubscribe true 表示首次 request unsubscribe 抛出 Error。
         */
        private SafetyGateway(final boolean failFirstRequestUnsubscribe) {
            this.failFirstRequestUnsubscribe = failFirstRequestUnsubscribe;
        }

        /**
         * 立即完成消息发送。
         *
         * @param message Kafka 消息；不可为空。
         * @return 已成功完成阶段；不可为空，线程安全。
         */
        @Override
        public CompletionStage<Void> send(final KafkaRpcMessage message) {
            return CompletableFuture.completedFuture(null);
        }

        /**
         * 接受默认 reply 订阅。
         *
         * @param topic reply topic；不可为空。
         * @param listener reply listener；不可为空。
         */
        @Override
        public void subscribe(final String topic, final KafkaRpcMessageListener listener) {
        }

        /**
         * 保存 request listener 以支持测试消息回放。
         *
         * @param topic request topic；不可为空。
         * @param group request group；不可为空。
         * @param listener request listener；不可为空。
         */
        @Override
        public void subscribe(
                final String topic,
                final String group,
                final KafkaRpcMessageListener listener) {
            requestListener = listener;
        }

        /**
         * 接受默认 reply 取消订阅。
         *
         * @param topic reply topic；不可为空。
         * @param listener reply listener；不可为空。
         */
        @Override
        public void unsubscribe(final String topic, final KafkaRpcMessageListener listener) {
        }

        /**
         * 首次按配置注入 Error，成功重试后清除 request listener。
         *
         * @param topic request topic；不可为空。
         * @param group request group；不可为空。
         * @param listener request listener；不可为空。
         * @throws AssertionError 配置首次故障注入时抛出。
         */
        @Override
        public void unsubscribe(
                final String topic,
                final String group,
                final KafkaRpcMessageListener listener) {
            int attempt = requestUnsubscribeCount.incrementAndGet();
            requestUnsubscribeRoutes.add(topic + "#" + group);
            if (failFirstRequestUnsubscribe && attempt == 1) {
                throw new AssertionError(FAILURE_SECRET + "-unsubscribe");
            }
            requestListener = null;
        }

        /** 记录网关关闭。 */
        @Override
        public void close() {
            closeCount.incrementAndGet();
        }

        /**
         * 向当前 request listener 回放请求。
         *
         * @param request RPC 请求；不可为空。
         */
        private void emitRequest(final RpcRequest request) {
            KafkaRpcEnvelopeCodec codec = new KafkaRpcEnvelopeCodec();
            requestListener.onMessage(new KafkaRpcMessage(
                    "request-topic",
                    request.correlationId(),
                    codec.encodeRequest(request)));
        }

        /**
         * 返回 request unsubscribe 次数。
         *
         * @return 调用次数；线程安全。
         */
        private int requestUnsubscribeCount() {
            return requestUnsubscribeCount.get();
        }

        /**
         * 返回 request unsubscribe 的 topic/group 有序快照。
         *
         * @return 有序、不可变、非空路由列表；线程安全。
         */
        private List<String> requestUnsubscribeRoutes() {
            return List.copyOf(requestUnsubscribeRoutes);
        }

        /**
         * 返回 close 次数。
         *
         * @return 调用次数；线程安全。
         */
        private int closeCount() {
            return closeCount.get();
        }
    }
}
