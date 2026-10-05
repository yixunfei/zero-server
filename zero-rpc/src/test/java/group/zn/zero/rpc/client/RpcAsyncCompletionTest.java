package group.zn.zero.rpc.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.core.error.SystemErrorCode;
import group.zn.zero.protocol.ProtocolDefinition;
import group.zn.zero.protocol.ProtocolDirection;
import group.zn.zero.protocol.codec.ProtocolCodec;
import group.zn.zero.rpc.RpcRequest;
import group.zn.zero.rpc.RpcResponse;
import group.zn.zero.rpc.codec.RpcCodecRegistry;
import group.zn.zero.rpc.common.RpcMethod;
import group.zn.zero.rpc.common.RpcResult;
import group.zn.zero.rpc.common.RpcService;
import group.zn.zero.rpc.error.RpcErrorCode;
import group.zn.zero.rpc.spi.RpcTransport;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;

/** 自定义编解码错误不能使客户端独立异步桥接阶段永久悬挂。 @author zn */
class RpcAsyncCompletionTest {
    /** 控制 transport 完成时机，覆盖客户端返回之后发生的解码 Error。 */
    @Test void decoderErrorCompletesClientFailureResult() {
        var response = new CompletableFuture<RpcResponse>();
        var codecs = new RpcCodecRegistry();
        codecs.register(Value.class, new ProtocolDefinition(1, "value", ProtocolDirection.SERVER_TO_CLIENT, 1),
                new ProtocolCodec<>() {
                    @Override public String name() { return "failing-value"; }
                    @Override public byte[] encode(final ProtocolDefinition definition, final Value value) {
                        return new byte[0];
                    }
                    @Override public Value decode(final ProtocolDefinition definition, final byte[] payload,
                            final Class<Value> type) { throw new AssertionError("decoder failed"); }
                });
        RpcTransport transport = new RpcTransport() {
            @Override public String name() { return "controlled"; }
            @Override public CompletionStage<RpcResponse> request(final RpcRequest request) { return response; }
            @Override public CompletionStage<Void> oneway(final RpcRequest request) {
                return CompletableFuture.completedFuture(null);
            }
        };
        Client client = new RpcClientFactory(transport, codecs).create(Client.class);
        var result = client.call().toCompletableFuture();
        response.complete(new RpcResponse("response", "trace", SystemErrorCode.OK, new byte[0]));
        assertTrue(result.isDone(), "decoder failure must terminate the returned stage");
        assertEquals(RpcErrorCode.INVOCATION_FAILED, result.join().errorCode());
        assertEquals("decoder failed", result.join().errorMsg());
    }

    /** 测试响应类型。 @author zn */
    record Value() { }

    /** 返回异步结果的服务契约。 @author zn */
    @RpcService(name = "async-completion")
    interface Client {
        /** @return 受控异步结果；线程安全。 */
        @RpcMethod(id = 1)
        CompletionStage<RpcResult<Value>> call();
    }
}
