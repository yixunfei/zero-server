package group.zn.zero.starter;

import group.zn.zero.actor.ActorMessage;
import group.zn.zero.actor.LaneKey;
import group.zn.zero.actor.handler.ActorHandler;
import group.zn.zero.core.config.MapZeroConfig;
import group.zn.zero.core.error.SystemErrorCode;
import group.zn.zero.event.BasicZeroEvent;
import group.zn.zero.event.EventType;
import group.zn.zero.log.InMemoryLogSink;
import group.zn.zero.log.LogLevel;
import group.zn.zero.log.LogOperation;
import group.zn.zero.log.LogResult;
import group.zn.zero.log.LogSource;
import group.zn.zero.log.LogType;
import group.zn.zero.log.ZeroLogRecord;
import group.zn.zero.net.ServerFrameHandler;
import group.zn.zero.net.ServerOptions;
import group.zn.zero.net.netty.NettyTcpServer;
import group.zn.zero.protocol.ProtocolDefinition;
import group.zn.zero.protocol.ProtocolDirection;
import group.zn.zero.protocol.ProtocolFrame;
import group.zn.zero.protocol.codec.ProtocolFrameCodec;
import group.zn.zero.protocol.codec.ZeroBinaryFrameCodec;
import group.zn.zero.rpc.RpcMode;
import group.zn.zero.rpc.RpcRequest;
import group.zn.zero.rpc.RpcResponse;
import group.zn.zero.rpc.spi.RpcHandler;
import group.zn.zero.runtime.api.GameRuntime;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * starter 完整本地装配 demo 启动入口。
 *
 * <p>该 demo 不依赖 Docker 或外部中间件，用于演示阶段二本地组件可以由 starter 统一装配：
 * 启动应用、注册协议、发布事件、派发 Actor、执行本地 RPC、读写缓存、触发持久化 flush、
 * 采样监控、写入日志，并通过本地 TCP server 完成一次协议帧请求响应。
 *
 * @author zn
 */
public final class ZeroServerFullLocalDemoStart {

    /**
     * demo 协议定义。
     */
    private static final ProtocolDefinition DEMO_PROTOCOL = new ProtocolDefinition(
            65001,
            "ZeroStarterFullLocalDemoProtocol",
            ProtocolDirection.CLIENT_TO_SERVER,
            1);

    private ZeroServerFullLocalDemoStart() {
    }

    /**
     * 命令行入口。
     *
     * @param args 命令行参数；当前版本不读取。
     * @throws Exception 当本地 demo 启动、网络收发或关闭失败时抛出。
     */
    public static void main(final String[] args) throws Exception {
        DemoResult result = runDemo();
        System.out.println(result.summary());
    }

    /**
     * 运行完整本地装配 demo。
     *
     * @return demo 结果；不可为空；线程安全。
     * @throws Exception 当本地 demo 启动、网络收发或关闭失败时抛出。
     */
    public static DemoResult runDemo() throws Exception {
        InMemoryLogSink logSink = new InMemoryLogSink();
        GameRuntime runtime = LocalRuntime.create(
                new MapZeroConfig(Map.of(
                        ZeroRuntimeConfigKeys.ZERO_MODE, "demo",
                        ZeroRuntimeConfigKeys.ZERO_NAME, "zeroServerFullLocalDemo")),
                logSink,
                ZeroRuntimeExecutors.singleThreaded("zero-starter-demo-logic"));
        ZeroServerApplication application = new ZeroServerApplication(runtime);
        ProtocolFrameCodec frameCodec = new ZeroBinaryFrameCodec();
        AtomicInteger eventCount = new AtomicInteger();
        AtomicInteger actorCount = new AtomicInteger();
        AtomicReference<String> handlerThreadName = new AtomicReference<>();

        application.start();
        NettyTcpServer server = newDemoServer(runtime, frameCodec, eventCount, actorCount, handlerThreadName);
        try {
            registerDemoComponents(runtime, eventCount, actorCount);
            server.start();
            ProtocolFrame response = exchange(server.boundPort(), frameCodec, new ProtocolFrame(
                    DEMO_PROTOCOL.id(),
                    DEMO_PROTOCOL.version(),
                    0,
                    null,
                    "ping".getBytes(StandardCharsets.UTF_8)));
            runtime.require(LocalRuntimeCapabilities.MONITOR_RUNTIME).collectOnce();
            return new DemoResult(
                    new String(response.payload(), StandardCharsets.UTF_8),
                    eventCount.get(),
                    actorCount.get(),
                    logSink.records().size(),
                    runtime.require(LocalRuntimeCapabilities.PERSISTENCE_MANAGER).running(),
                    application.running(),
                    handlerThreadName.get());
        } finally {
            server.stop();
            application.stop();
        }
    }

    private static void registerDemoComponents(
            final GameRuntime runtime,
            final AtomicInteger eventCount,
            final AtomicInteger actorCount) {
        runtime.require(LocalRuntimeCapabilities.PROTOCOL_REGISTRY).register(DEMO_PROTOCOL);
        runtime.require(LocalRuntimeCapabilities.ACTOR_SCHEDULER).register(
                DemoActorPayload.class, ActorHandler.sync((context, message) -> {
            actorCount.incrementAndGet();
        }));
        runtime.require(LocalRuntimeCapabilities.EVENT_BUS).register(EventType.CLIENT_PROTOCOL, event -> {
            eventCount.incrementAndGet();
            return runtime.require(LocalRuntimeCapabilities.ACTOR_SCHEDULER).dispatch(new ActorMessage(
                    "demo-actor-" + event.eventId(),
                    LaneKey.player("demo-player"),
                    event.traceId(),
                    new DemoActorPayload(event.eventId())));
        }, 0);
        runtime.require(LocalRuntimeCapabilities.RPC_HANDLER_REGISTRY).register(
                "starter-demo", "echo", RpcHandler.sync(request -> new RpcResponse(
                request.correlationId(),
                request.traceId(),
                SystemErrorCode.OK,
                request.payload())));
        runtime.require(LocalRuntimeCapabilities.CACHE_SERVICE)
                .put("demo-key", "demo-value").toCompletableFuture().join();
        runtime.require(LocalRuntimeCapabilities.PERSISTENCE_MANAGER).flushNow().toCompletableFuture().join();
    }

    private static NettyTcpServer newDemoServer(
            final GameRuntime runtime,
            final ProtocolFrameCodec frameCodec,
            final AtomicInteger eventCount,
            final AtomicInteger actorCount,
            final AtomicReference<String> handlerThreadName) {
        ServerFrameHandler handler = (connection, frame) -> {
            handlerThreadName.set(Thread.currentThread().getName());
            runtime.require(LocalRuntimeCapabilities.EVENT_BUS).publish(new BasicZeroEvent(
                    "demo-event-" + frame.protocolId(),
                    EventType.CLIENT_PROTOCOL,
                    "trace-demo-local")).toCompletableFuture().join();
            RpcResponse rpcResponse = runtime.require(LocalRuntimeCapabilities.RPC_TRANSPORT).request(new RpcRequest(
                    "demo-correlation",
                    "demo-reply",
                    "starter-demo",
                    "echo",
                    "trace-demo-local",
                    Instant.now().plusSeconds(3),
                    RpcMode.REQUEST_RESPONSE,
                    frame.payload())).toCompletableFuture().join();
            Object cacheValue = runtime.require(LocalRuntimeCapabilities.CACHE_SERVICE)
                    .get("demo-key").toCompletableFuture()
                    .join()
                    .orElse("missing");
            runtime.require(LocalRuntimeCapabilities.LOG_APPENDER).append(ZeroLogRecord.create(
                    Instant.now(),
                    LogLevel.INFO,
                    LogType.RUNTIME,
                    new LogSource("zeroServerFullLocalDemo", "demo", "zero-server-starter"),
                    new LogOperation("handle-local-demo-frame", LogResult.SUCCESS, null),
                    "trace-demo-local",
                    "full local demo handled frame",
                    Map.of("connectionId", connection.connectionId())));
            String responseText = "demo=ok"
                    + "|event=" + eventCount.get()
                    + "|actor=" + actorCount.get()
                    + "|rpc=" + new String(rpcResponse.payload(), StandardCharsets.UTF_8)
                    + "|cache=" + cacheValue;
            return CompletableFuture.completedFuture(List.of(new ProtocolFrame(
                    frame.protocolId(),
                    frame.protocolVersion(),
                    frame.flags(),
                    frame.extension(),
                    responseText.getBytes(StandardCharsets.UTF_8))));
        };
        return new NettyTcpServer(
                ServerOptions.tcp("127.0.0.1", 0).withIoThreads(1, 1),
                frameCodec,
                handler,
                new group.zn.zero.net.ConnectionListener() {
                },
                runtime.require(LocalRuntimeCapabilities.EXECUTORS).logicExecutor());
    }

    private static ProtocolFrame exchange(
            final int port,
            final ProtocolFrameCodec frameCodec,
            final ProtocolFrame request) throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 3000);
            socket.setSoTimeout(3000);
            DataOutputStream output = new DataOutputStream(socket.getOutputStream());
            byte[] requestBytes = frameCodec.encode(request);
            output.writeInt(requestBytes.length);
            output.write(requestBytes);
            output.flush();

            DataInputStream input = new DataInputStream(socket.getInputStream());
            int responseLength = input.readInt();
            byte[] responseBytes = input.readNBytes(responseLength);
            return frameCodec.decode(responseBytes);
        }
    }

    /**
     * demo 运行结果。
     *
     * @param responseText 响应文本。
     * @param eventCount 事件处理次数。
     * @param actorCount Actor 处理次数。
     * @param logCount 日志数量。
     * @param persistenceRunning 返回结果时持久化管理器是否运行中。
     * @param applicationRunning 返回结果时应用是否运行中。
     * @param handlerThreadName 网络 handler 执行线程名。
     * @author zn
     */
    public record DemoResult(
            String responseText,
            int eventCount,
            int actorCount,
            int logCount,
            boolean persistenceRunning,
            boolean applicationRunning,
            String handlerThreadName) {

        /**
         * 创建 demo 运行结果。
         *
         * @throws NullPointerException 当响应文本为空时抛出。
         */
        public DemoResult {
            java.util.Objects.requireNonNull(responseText, "responseText");
        }

        /**
         * 返回命令行摘要。
         *
         * @return 摘要文本；不可为空；线程安全。
         */
        public String summary() {
            return responseText
                    + "|logs=" + logCount
                    + "|persistenceRunning=" + persistenceRunning
                    + "|applicationRunning=" + applicationRunning
                    + "|handlerThread=" + handlerThreadName;
        }
    }

    /**
     * demo Actor 消息体。
     *
     * @param eventId 事件标识。
     * @author zn
     */
    private record DemoActorPayload(String eventId) {
    }
}
