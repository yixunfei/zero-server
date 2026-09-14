package group.zn.zero.examples.composition;

import group.zn.zero.rpc.client.RpcClientFactory;
import group.zn.zero.rpc.codec.RpcCodecRegistry;
import group.zn.zero.rpc.common.RpcMethod;
import group.zn.zero.rpc.common.RpcResult;
import group.zn.zero.rpc.common.RpcService;
import group.zn.zero.rpc.server.RpcServiceBinder;
import group.zn.zero.runtime.bootstrap.RuntimeBasics;
import group.zn.zero.runtime.rpc.RpcRuntime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;

/** 中心与逻辑模块共用 RPC 业务接口的本地示例，不依赖任何外部中间件。 */
public final class CenterLogicApplication {

    private CenterLogicApplication() {
    }

    /**
     * 运行一次中心—逻辑调用并退出；不创建线程或监听端口。
     * @param args 未使用的命令行参数。
     */
    public static void main(final String[] args) {
        System.out.println("center-logic=ok|heartbeats=" + runDemo());
    }

    /**
     * 组合中心实现与逻辑客户端，使用同一套业务接口完成调用。
     * @return 中心收到的心跳数；每次调用有独立状态，线程安全。
     * @throws RuntimeException 装配、RPC 或关闭失败时抛出。
     */
    public static int runDemo() {
        try (var runtime = RuntimeBasics.builder().install(RpcRuntime.module()).build()) {
            var codecs = new RpcCodecRegistry();
            var center = new CenterService();
            var registry = runtime.require(RpcRuntime.RPC_HANDLER_REGISTRY);
            try (var binding = new RpcServiceBinder(registry, codecs).bind(CenterRpc.class, center)) {
                runtime.start();
                CenterRpc client = new RpcClientFactory(runtime.require(RpcRuntime.RPC_TRANSPORT), codecs)
                        .create(CenterRpc.class);
                // 这里只在 main/test 边界等待；Actor/Netty 业务应继续组合 CompletionStage。
                new LogicService(client).notifyCenter().toCompletableFuture().join();
                return center.heartbeats.get();
            }
        }
    }

    /** 可放进业务 common 工件的中心契约；没有 Kafka 或 Nacos 类型。 */
    @RpcService(name = "game.center", version = 1)
    public interface CenterRpc {
        /**
         * 上报一次示例心跳；并发安全，在实现中更新计数，不代表持久节点注册。
         * @return 非空异步 RPC 结果；失败通过结果或异常阶段传播。
         */
        @RpcMethod(id = 1)
        CompletionStage<RpcResult<Void>> heartbeat();
    }

    /** 中心端示例实现；应用级状态只在此处拥有。 */
    public static final class CenterService implements CenterRpc {
        /** 并发安全的示例计数，不作为生产健康判定。 */
        private final AtomicInteger heartbeats = new AtomicInteger();

        /** {@inheritDoc} */
        @Override
        public CompletionStage<RpcResult<Void>> heartbeat() {
            heartbeats.incrementAndGet();
            return CompletableFuture.completedFuture(RpcResult.success(null));
        }
    }

    /** 逻辑端业务只依赖中心接口，不关心传输与发现实现。 */
    private static final class LogicService {
        /** 由装配入口注入的中心接口。 */
        private final CenterRpc center;

        private LogicService(final CenterRpc center) {
            this.center = center;
        }

        private CompletionStage<Void> notifyCenter() {
            return center.heartbeat().thenApply(RpcResult::orThrow);
        }
    }
}
