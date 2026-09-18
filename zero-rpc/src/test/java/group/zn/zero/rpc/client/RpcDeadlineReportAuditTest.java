package group.zn.zero.rpc.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** 受控传输验证同步等待使用剩余预算。 @author zn */
class RpcDeadlineReportAuditTest {
    /** 传输调用已消耗预算后，不能再给等待阶段完整预算。 */
    @Test void exhaustedTransportBudgetDoesNotRestartTheWait() {
        RpcTransport transport = new RpcTransport() {
            /** @return 测试传输名称。 */
            @Override public String name() { return "deadline-test"; }
            /** @return 延迟得到的未完成响应。 */
            @Override public CompletionStage<RpcResponse> request(RpcRequest request) {
                try { Thread.sleep(40); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
                return new NoWaitFuture<>();
            }
            /** @return 单向完成信号。 */
            @Override public CompletionStage<Void> oneway(RpcRequest request) { return CompletableFuture.completedFuture(null); }
        };
        Client client = new RpcClientFactory(transport, new RpcCodecRegistry()).create(Client.class);
        assertEquals(RpcErrorCode.REQUEST_TIMEOUT, client.call().errorCode());
    }
    /** 传播到派生阶段的探针，检查是否错误地重启等待。 */
    private static final class NoWaitFuture<T> extends CompletableFuture<T> {
        /** 已耗尽预算时不可等待。 */
        @Override public T get(long timeout, TimeUnit unit) {
            throw new AssertionError("expired request must not restart waiting");
        }
        /** @return 保持探针能力的派生阶段；线程安全。 */
        @Override public <U> CompletableFuture<U> newIncompleteFuture() { return new NoWaitFuture<>(); }
    }
    /** 无参数 RPC 合约。 */
    @RpcService(name = "deadline")
    interface Client {
        /** @return 调用结果。 */
        @RpcMethod(id = 1, timeoutMillis = 10)
        RpcResult<Void> call();
    }
}
