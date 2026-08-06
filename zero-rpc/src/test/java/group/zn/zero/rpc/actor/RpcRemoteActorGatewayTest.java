package group.zn.zero.rpc.actor;

import static org.junit.jupiter.api.Assertions.assertEquals;

import group.zn.zero.actor.ActorMessage;
import group.zn.zero.actor.LaneKey;
import group.zn.zero.actor.handler.ActorHandler;
import group.zn.zero.actor.remote.ActorAddress;
import group.zn.zero.actor.remote.ActorDispatchOptions;
import group.zn.zero.actor.remote.ActorRoute;
import group.zn.zero.actor.scheduler.LocalActorScheduler;
import group.zn.zero.protocol.ProtocolDefinition;
import group.zn.zero.protocol.ProtocolDirection;
import group.zn.zero.protocol.buffer.ZeroReader;
import group.zn.zero.protocol.buffer.ZeroWriter;
import group.zn.zero.protocol.codec.GeneratedProtocolCodec;
import group.zn.zero.protocol.codec.ZeroPayloadCodec;
import group.zn.zero.rpc.codec.RpcCodecRegistry;
import group.zn.zero.rpc.discovery.RoundRobinRpcServiceResolver;
import group.zn.zero.rpc.discovery.RpcDiscoveryMetadata;
import group.zn.zero.rpc.discovery.RpcServiceInstance;
import group.zn.zero.rpc.discovery.RpcServiceQuery;
import group.zn.zero.rpc.local.InMemoryRpcTransport;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * RPC 远程 Actor gateway 测试。
 *
 * @author zn
 */
class RpcRemoteActorGatewayTest {

    /**
     * 验证 RPC oneway 能把 Actor 消息投递到接收端调度器。
     *
     * @throws Exception 当等待投递完成失败时抛出。
     */
    @Test
    void rpcGatewayShouldDispatchActorMessageToReceiverScheduler() throws Exception {
        RpcCodecRegistry codecRegistry = codecRegistry();
        RpcActorMessageCodec messageCodec = new RpcActorMessageCodec(codecRegistry);
        InMemoryRpcTransport transport = new InMemoryRpcTransport();
        LocalActorScheduler receiverScheduler = new LocalActorScheduler();
        AtomicReference<String> handled = new AtomicReference<>();
        receiverScheduler.register(RemoteCommand.class, ActorHandler.sync((context, message) -> {
            RemoteCommand command = (RemoteCommand) message.payload();
            handled.set(context.traceId() + ":" + command.value());
        }));
        new RpcRemoteActorReceiver(transport, receiverScheduler, messageCodec)
                .register("scene-actor", RpcRemoteActorGateway.DEFAULT_METHOD_NAME, "", "");
        RpcRemoteActorGateway gateway = new RpcRemoteActorGateway(transport, messageCodec);
        LaneKey laneKey = LaneKey.scene("scene-1001");
        ActorRoute route = ActorRoute.remote(
                ActorAddress.remote(laneKey, "scene-actor", 1, "node-a", "zone-a"),
                RpcRemoteActorGateway.DEFAULT_METHOD_NAME,
                "memory",
                "",
                "",
                laneKey.value(),
                Map.of());

        gateway.dispatch(
                        new ActorMessage("msg-1", laneKey, "trace-rpc", new RemoteCommand("touch")),
                        route,
                        ActorDispatchOptions.defaults())
                .toCompletableFuture()
                .get();

        assertEquals("trace-rpc:touch", handled.get());
    }

    /**
     * 验证 RPC 服务发现解析器能产出远程 Actor route。
     */
    @Test
    void rpcRouteResolverShouldMapServiceInstanceToActorRoute() {
        RpcServiceInstance instance = new RpcServiceInstance(
                "scene-actor",
                1,
                "node-a",
                "kafka",
                "actor-topic",
                "actor-group",
                "127.0.0.1",
                0,
                RpcDiscoveryMetadata.DEFAULT_GROUP_NAME,
                RpcDiscoveryMetadata.DEFAULT_CLUSTER_NAME,
                "zone-a",
                true,
                true,
                1.0D,
                Map.of());
        RoundRobinRpcServiceResolver serviceResolver = new RoundRobinRpcServiceResolver(List.of(instance));
        RpcActorRouteResolver routeResolver = new RpcActorRouteResolver(
                serviceResolver,
                ignored -> RpcServiceQuery.of("scene-actor", 1).withTransport("kafka"),
                RpcRemoteActorGateway.DEFAULT_METHOD_NAME);

        ActorRoute route = routeResolver.resolve(new ActorMessage(
                "msg-2",
                LaneKey.scene("scene-1002"),
                "trace-route",
                new RemoteCommand("route")));

        assertEquals("scene-actor", route.address().ownerServiceName());
        assertEquals("actor-topic", route.requestTopic());
        assertEquals("actor-group", route.consumerGroup());
        assertEquals("scene-1002", route.partitionKey());
    }

    private RpcCodecRegistry codecRegistry() {
        RpcCodecRegistry registry = new RpcCodecRegistry();
        registry.register(
                RemoteCommand.class,
                new ProtocolDefinition(9201, "rpc.actor.remote.command", ProtocolDirection.CLIENT_TO_SERVER, 1),
                new GeneratedProtocolCodec<>(RemoteCommandCodec.INSTANCE));
        return registry;
    }

    /**
     * 远程 Actor 测试命令。
     *
     * @param value 命令值。
     * @author zn
     */
    private record RemoteCommand(String value) {
    }

    /**
     * 远程 Actor 测试命令 codec。
     *
     * @author zn
     */
    private static final class RemoteCommandCodec implements ZeroPayloadCodec<RemoteCommand> {

        /**
         * 单例。
         */
        private static final RemoteCommandCodec INSTANCE = new RemoteCommandCodec();

        private RemoteCommandCodec() {
        }

        /**
         * 返回 codec 名称。
         *
         * @return 名称；不可为空；线程安全。
         */
        @Override
        public String name() {
            return "rpc-actor-remote-command";
        }

        /**
         * 返回消息类型。
         *
         * @return 消息类型；不可为空；线程安全。
         */
        @Override
        public Class<RemoteCommand> messageType() {
            return RemoteCommand.class;
        }

        /**
         * 写入命令。
         *
         * @param writer 写入器；不可为空。
         * @param message 消息；不可为空。
         */
        @Override
        public void write(final ZeroWriter writer, final RemoteCommand message) {
            int marker = writer.beginObject();
            writer.writeString(message.value());
            writer.endObject(marker);
        }

        /**
         * 读取命令。
         *
         * @param reader 读取器；不可为空。
         * @return 命令；不可为空；线程安全。
         */
        @Override
        public RemoteCommand read(final ZeroReader reader) {
            int end = reader.beginObject();
            String value = "";
            if (reader.hasRemainingInObject(end)) {
                value = reader.readString();
            }
            reader.endObject(end);
            return new RemoteCommand(value);
        }
    }
}
