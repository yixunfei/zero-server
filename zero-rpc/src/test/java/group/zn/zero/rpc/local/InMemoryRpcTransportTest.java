package group.zn.zero.rpc.local;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import group.zn.zero.core.error.ZeroException;
import group.zn.zero.core.error.SystemErrorCode;
import group.zn.zero.rpc.RpcMode;
import group.zn.zero.rpc.RpcRequest;
import group.zn.zero.rpc.RpcResponse;
import group.zn.zero.rpc.error.RpcErrorCode;
import group.zn.zero.rpc.spi.RpcHandler;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * 本地 RPC 传输测试。
 *
 * @author zn
 */
class InMemoryRpcTransportTest {

    /**
     * 验证 request / oneway 均可按本地路由工作。
     */
    @Test
    void transportShouldHandleRequestAndOneway() {
        InMemoryRpcTransport transport = new InMemoryRpcTransport();
        transport.register("demo", "echo", sync(request -> new RpcResponse(
                request.correlationId(),
                request.traceId(),
                SystemErrorCode.OK,
                request.payload())));

        RpcRequest request = new RpcRequest(
                "corr-1",
                "demo-topic",
                "demo",
                "echo",
                "trace-1",
                Instant.now().plusSeconds(60),
                RpcMode.REQUEST_RESPONSE,
                "ping".getBytes());

        RpcResponse response = transport.request(request).toCompletableFuture().join();
        transport.oneway(new RpcRequest(
                "corr-2",
                "demo-topic",
                "demo",
                "echo",
                "trace-2",
                Instant.now().plusSeconds(60),
                RpcMode.ONEWAY,
                "pong".getBytes())).toCompletableFuture().join();

        assertEquals("ping", new String(response.payload(), StandardCharsets.UTF_8));
    }

    /**
     * 验证过期请求会被拒绝。
     */
    @Test
    void transportShouldRejectExpiredRequest() {
        InMemoryRpcTransport transport = new InMemoryRpcTransport();

        ZeroException exception = assertThrows(ZeroException.class, () -> transport.request(new RpcRequest(
                "corr-expired",
                "demo-topic",
                "demo",
                "echo",
                "trace-expired",
                Instant.now().minusSeconds(1),
                RpcMode.REQUEST_RESPONSE,
                "ping".getBytes())));

        assertEquals(RpcErrorCode.REQUEST_TIMEOUT, exception.errorCode());
    }

    /**
     * 验证传输不可用时会快速失败。
     */
    @Test
    void transportShouldFailWhenUnavailable() {
        InMemoryRpcTransport transport = new InMemoryRpcTransport();
        transport.available(false);

        ZeroException exception = assertThrows(ZeroException.class, () -> transport.request(new RpcRequest(
                "corr-off",
                "demo-topic",
                "demo",
                "echo",
                "trace-off",
                Instant.now().plusSeconds(60),
                RpcMode.REQUEST_RESPONSE,
                "ping".getBytes())));

        assertEquals(RpcErrorCode.TRANSPORT_UNAVAILABLE, exception.errorCode());
    }

    private RpcHandler sync(final java.util.function.Function<RpcRequest, RpcResponse> handler) {
        return RpcHandler.sync(handler);
    }
}
