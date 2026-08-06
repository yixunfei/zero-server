package group.zn.zero.starter.production;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.core.lifecycle.LifecycleState;
import group.zn.zero.rpc.RpcRequest;
import group.zn.zero.rpc.RpcResponse;
import group.zn.zero.rpc.kafka.KafkaRpcSettings;
import group.zn.zero.rpc.observer.RpcTransportObserver;
import group.zn.zero.rpc.spi.RpcHandler;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;

/**
 * {@link KafkaRpcLifecycleAdapter} handler 回放失败资源安全测试。
 *
 * @author zn
 */
class KafkaRpcLifecycleAdapterReplayTest {

    /** 用于反证回放和清理异常原值不会进入公开异常图的敏感哨兵。 */
    private static final String SECRET = "PAF1-KAFKA-REPLAY-SECRET-SENTINEL";

    /**
     * 验证第二个 handler 回放失败时不发布 delegate，关闭已创建资源并安全保留清理失败。
     */
    @Test
    void replayFailureShouldRollbackUnpublishedAdapterAndSanitizeCleanupFailure() {
        FailingReplayResource resource = new FailingReplayResource();
        KafkaRpcLifecycleAdapter lifecycle = new KafkaRpcLifecycleAdapter(
                KafkaRpcSettings.defaults("127.0.0.1:9092"),
                RpcTransportObserver.noop(),
                (settings, observer) -> resource);
        lifecycle.register("service-one", "method-one", testHandler());
        lifecycle.register("service-two", "method-two", testHandler());
        lifecycle.register("service-three", "method-three", testHandler());

        ProductionAdapterException failure = assertThrows(
                ProductionAdapterException.class,
                lifecycle::start);

        assertEquals(ZeroProductionRuntimeBuilder.ADAPTER_KAFKA_RPC, failure.adapterName());
        assertEquals(ProductionAdapterFailurePhase.STARTUP, failure.failurePhase());
        assertSame(ProductionAdapterErrorCode.STARTUP_FAILED, failure.errorCode());
        assertEquals(ProductionAdapterErrorCode.STARTUP_FAILED.message(), failure.message());
        assertNull(failure.getCause());
        assertEquals(2, resource.registrationCalls());
        assertEquals(1, resource.closeCalls());
        assertFalse(lifecycle.resourcePublished());
        assertTrue(lifecycle.delegate().isEmpty());
        assertEquals(LifecycleState.FAILED, lifecycle.state());

        assertEquals(1, failure.getSuppressed().length);
        ProductionAdapterException rollbackFailure = assertInstanceOf(
                ProductionAdapterException.class,
                failure.getSuppressed()[0]);
        assertEquals(ProductionAdapterFailurePhase.ROLLBACK, rollbackFailure.failurePhase());
        assertSame(ProductionAdapterErrorCode.ROLLBACK_FAILED, rollbackFailure.errorCode());
        assertNull(rollbackFailure.getCause());
        assertFalse(stackTrace(failure).contains(SECRET), stackTrace(failure));
    }

    /**
     * 返回永远不应在本测试中执行的占位 RPC handler。
     *
     * @return RPC handler；不可为空，无共享可变状态。
     */
    private RpcHandler testHandler() {
        return request -> CompletableFuture.failedFuture(
                new AssertionError("replayed test handler must not execute"));
    }

    /**
     * 把完整异常图打印为文本，供敏感哨兵反证。
     *
     * @param failure 待打印异常；不可为空。
     * @return 完整堆栈文本；不可为空，调用方可变，方法不修改异常图。
     */
    private String stackTrace(final Throwable failure) {
        StringWriter writer = new StringWriter();
        try (PrintWriter printer = new PrintWriter(writer)) {
            failure.printStackTrace(printer);
        }
        return writer.toString();
    }

    /**
     * 第二次注册失败且关闭也失败的发布前 Kafka 资源。
     *
     * @author zn
     */
    private static final class FailingReplayResource implements KafkaRpcAdapterResource {

        /** handler 回放调用次数。 */
        private int registrationCalls;

        /** 关闭调用次数。 */
        private int closeCalls;

        /**
         * 返回 handler 回放调用次数。
         *
         * @return 非负调用次数，仅由测试线程读取。
         */
        private int registrationCalls() {
            return registrationCalls;
        }

        /**
         * 返回关闭调用次数。
         *
         * @return 非负调用次数，仅由测试线程读取。
         */
        private int closeCalls() {
            return closeCalls;
        }

        /**
         * 记录回放，并在第二次回放时注入严重错误。
         *
         * @param serviceName 服务名；不可为空。
         * @param methodName 方法名；不可为空。
         * @param topic request topic；不可为空，可为空串。
         * @param group consumer group；不可为空，可为空串。
         * @param handler RPC handler；不可为空。
         * @throws AssertionError 第二次调用时始终抛出。
         */
        @Override
        public void register(
                final String serviceName,
                final String methodName,
                final String topic,
                final String group,
                final RpcHandler handler) {
            registrationCalls++;
            if (registrationCalls == 2) {
                throw new AssertionError(SECRET + "-replay");
            }
        }

        /** 任何注销调用都表示回放失败路径越过了预期边界。 */
        @Override
        public void unregister(final String serviceName, final String methodName) {
            throw new AssertionError("failed replay resource must not unregister");
        }

        /** 任何 request 调用都表示测试越过了启动回放边界。 */
        @Override
        public CompletionStage<RpcResponse> request(final RpcRequest request) {
            return CompletableFuture.failedFuture(
                    new AssertionError("failed replay resource must not send request"));
        }

        /** 任何 oneway 调用都表示测试越过了启动回放边界。 */
        @Override
        public CompletionStage<Void> oneway(final RpcRequest request) {
            return CompletableFuture.failedFuture(
                    new AssertionError("failed replay resource must not send oneway"));
        }

        /** 故障注入资源不公开真实 Kafka Adapter。 */
        @Override
        public Optional<group.zn.zero.rpc.kafka.KafkaRpcAdapter> exposedAdapter() {
            return Optional.empty();
        }

        /**
         * 记录补偿关闭并注入原始关闭失败。
         *
         * @throws IllegalStateException 始终抛出原始关闭失败。
         */
        @Override
        public void close() {
            closeCalls++;
            throw new IllegalStateException(SECRET + "-close");
        }
    }
}
