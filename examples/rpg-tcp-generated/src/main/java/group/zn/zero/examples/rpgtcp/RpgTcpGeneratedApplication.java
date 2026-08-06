package group.zn.zero.examples.rpgtcp;

import group.zn.zero.examples.rpgtcp.generated.bo.RpgTcpQueryPlayerEventBO;
import group.zn.zero.examples.rpgtcp.generated.dto.RpgTcpQueryPlayerProtocolDTO;
import group.zn.zero.examples.rpgtcp.generated.dto.codec.RpgTcpQueryPlayerProtocolDTOCodec;
import group.zn.zero.examples.rpgtcp.generated.protocol.ProtocolIds;
import group.zn.zero.examples.rpgtcp.generated.protocol.dispatch.GeneratedProtocolDispatcher;
import group.zn.zero.logic.session.LogicSession;
import group.zn.zero.logic.session.LogicSessionManager;
import group.zn.zero.logic.session.StandardLogicSessionAttributes;
import group.zn.zero.net.ConnectionListener;
import group.zn.zero.net.IConnection;
import group.zn.zero.net.ServerFrameHandler;
import group.zn.zero.net.ServerOptions;
import group.zn.zero.net.netty.NettyTcpServer;
import group.zn.zero.protocol.ProtocolFrame;
import group.zn.zero.protocol.buffer.ZeroWriter;
import group.zn.zero.protocol.codec.ProtocolFrameCodec;
import group.zn.zero.protocol.codec.ZeroBinaryFrameCodec;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * RPG TCP generated dispatcher 本地示例。
 *
 * <p>该示例只验证真实 TCP 客户端请求可以通过 NettyTcpServer 进入生成 dispatcher 和手写 BO。
 * 它不包含账号鉴权、握手、心跳、限流、重连或生产连接治理逻辑。
 *
 * @author zn
 */
public final class RpgTcpGeneratedApplication {

    /**
     * 本地监听地址。
     */
    private static final String HOST = "127.0.0.1";

    /**
     * 示例渠道编码。
     */
    private static final String CHANNEL_CODE = "tcp-example";

    /**
     * 示例业务处理线程名称。
     */
    private static final String HANDLER_THREAD = "zero-example-rpg-tcp";

    /**
     * 示例玩家标识。
     */
    private static final long UID = 1001L;

    /**
     * 示例链路标识。
     */
    private static final String TRACE_ID = "trace-rpg-tcp-1";

    private RpgTcpGeneratedApplication() {
    }

    /**
     * 启动 RPG TCP generated dispatcher 示例。
     *
     * @param args 命令行参数；当前未使用；可以为空。
     * @throws Exception 当 TCP 启动、客户端收发或资源关闭失败时抛出。
     */
    public static void main(final String[] args) throws Exception {
        System.out.println(runDemo().summaryLine());
    }

    /**
     * 执行真实 TCP 客户端到 generated dispatcher 的最小闭环。
     *
     * <p>流程如下：
     *
     * <pre>
     * RpgTcp.si 生成 DTO / codec / BO / dispatcher
     *   -> 手写 BO 注册到 GeneratedProtocolDispatcher
     *   -> NettyTcpServer 接收 ProtocolFrame
     *   -> LogicSessionManager 记录连接 session
     *   -> dispatcher.dispatch(protocolId, payload)
     *   -> JDK Socket 客户端读回响应
     * </pre>
     *
     * @return 示例运行结果；不可为空；结果不可变；线程安全。
     * @throws Exception 当 TCP 启动、客户端收发或资源关闭失败时抛出。
     */
    public static TcpDemoResult runDemo() throws Exception {
        ProtocolFrameCodec frameCodec = new ZeroBinaryFrameCodec();
        GeneratedProtocolDispatcher dispatcher = new GeneratedProtocolDispatcher();
        BusinessHandlers businessHandlers = new BusinessHandlers();
        dispatcher.registerRpgTcpQueryPlayerEventBO(businessHandlers);
        ProtocolFrame requestFrame = requestFrame();
        LogicSessionManager sessionManager = new LogicSessionManager();
        CountDownLatch closeLatch = new CountDownLatch(1);
        AtomicBoolean dispatched = new AtomicBoolean(false);
        AtomicReference<String> channel = new AtomicReference<>("");
        AtomicLong requests = new AtomicLong(0L);
        AtomicReference<String> handlerThread = new AtomicReference<>("");
        ExecutorService handlerExecutor = newHandlerExecutor();
        ConnectionListener listener = closeSessionOnDisconnect(sessionManager, closeLatch);
        ServerFrameHandler handler = createFrameHandler(
                dispatcher,
                businessHandlers,
                sessionManager,
                dispatched,
                channel,
                requests,
                handlerThread);
        NettyTcpServer server = new NettyTcpServer(
                ServerOptions.tcp(HOST, 0).withIoThreads(1, 1),
                frameCodec,
                handler,
                listener,
                handlerExecutor);

        try {
            server.start();
            ProtocolFrame response = exchange(server.boundPort(), frameCodec, requestFrame);
            boolean sessionsClosed = closeLatch.await(Duration.ofSeconds(3).toMillis(), TimeUnit.MILLISECONDS)
                    && sessionManager.sessions().isEmpty();
            if (response.protocolId() != ProtocolIds.RPG_TCP_QUERY_PLAYER_PROTOCOL) {
                throw new IllegalStateException("unexpected response protocol: " + response.protocolId());
            }
            return new TcpDemoResult(
                    response.protocolId(),
                    dispatched.get(),
                    businessHandlers.uid(),
                    businessHandlers.traceId(),
                    channel.get(),
                    requests.get(),
                    handlerThread.get(),
                    sessionsClosed);
        } finally {
            try {
                server.stop();
            } finally {
                shutdown(handlerExecutor);
            }
        }
    }

    private static ExecutorService newHandlerExecutor() {
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, HANDLER_THREAD);
            thread.setDaemon(true);
            return thread;
        });
    }

    private static ConnectionListener closeSessionOnDisconnect(
            final LogicSessionManager sessionManager,
            final CountDownLatch closeLatch) {
        return new ConnectionListener() {
            @Override
            public void onClose(final IConnection connection) {
                sessionManager.close(connection);
                closeLatch.countDown();
            }
        };
    }

    private static ServerFrameHandler createFrameHandler(
            final GeneratedProtocolDispatcher dispatcher,
            final BusinessHandlers businessHandlers,
            final LogicSessionManager sessionManager,
            final AtomicBoolean dispatched,
            final AtomicReference<String> channel,
            final AtomicLong requests,
            final AtomicReference<String> handlerThread) {
        return (connection, frame) -> {
            handlerThread.set(Thread.currentThread().getName());
            LogicSession session = sessionManager.open(connection);
            session.attributes().put(StandardLogicSessionAttributes.CHANNEL_CODE, CHANNEL_CODE);
            sessionManager.markRequest(session);
            channel.set(session.attributes().get(StandardLogicSessionAttributes.CHANNEL_CODE).orElseThrow());
            requests.set(session.attributes().get(StandardLogicSessionAttributes.REQUEST_COUNT).orElseThrow());
            boolean handled = dispatcher.dispatch(frame.protocolId(), frame.payload());
            dispatched.set(handled);
            String responseText = "dispatched=" + handled
                    + "|uid=" + businessHandlers.uid()
                    + "|trace=" + businessHandlers.traceId()
                    + "|channel=" + channel.get()
                    + "|requests=" + requests.get();
            ProtocolFrame response = new ProtocolFrame(
                    frame.protocolId(),
                    frame.protocolVersion(),
                    frame.flags(),
                    frame.extension(),
                    responseText.getBytes(StandardCharsets.UTF_8));
            return CompletableFuture.completedFuture(List.of(response));
        };
    }

    private static ProtocolFrame requestFrame() {
        RpgTcpQueryPlayerProtocolDTO request = new RpgTcpQueryPlayerProtocolDTO();
        request.uid = UID;
        request.traceId = TRACE_ID;
        try (ZeroWriter writer = new ZeroWriter()) {
            RpgTcpQueryPlayerProtocolDTOCodec.INSTANCE.write(writer, request);
            return new ProtocolFrame(
                    ProtocolIds.RPG_TCP_QUERY_PLAYER_PROTOCOL,
                    1,
                    0,
                    null,
                    writer.toByteArray());
        }
    }

    private static ProtocolFrame exchange(
            final int port,
            final ProtocolFrameCodec frameCodec,
            final ProtocolFrame request) throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(HOST, port), 3000);
            socket.setSoTimeout(3000);
            DataOutputStream output = new DataOutputStream(socket.getOutputStream());
            byte[] requestBytes = frameCodec.encode(request);
            output.writeInt(requestBytes.length);
            output.write(requestBytes);
            output.flush();

            DataInputStream input = new DataInputStream(socket.getInputStream());
            int responseLength = input.readInt();
            byte[] responseBytes = input.readNBytes(responseLength);
            if (responseBytes.length != responseLength) {
                throw new IllegalStateException("incomplete response frame");
            }
            return frameCodec.decode(responseBytes);
        }
    }

    private static void shutdown(final ExecutorService executor) throws InterruptedException {
        executor.shutdownNow();
        if (!executor.awaitTermination(Duration.ofSeconds(3).toMillis(), TimeUnit.MILLISECONDS)) {
            throw new IllegalStateException("handler executor did not stop");
        }
    }

    /**
     * 示例生成 BO 实现。
     *
     * @author zn
     */
    private static final class BusinessHandlers implements RpgTcpQueryPlayerEventBO {

        /**
         * BO 收到的玩家标识。
         */
        private final AtomicLong uid = new AtomicLong(-1L);

        /**
         * BO 收到的链路标识。
         */
        private final AtomicReference<String> traceId = new AtomicReference<>("");

        /**
         * 处理查询玩家协议事件。
         *
         * @param request 协议请求 DTO；不可为空。
         */
        @Override
        public void queryPlayer(final RpgTcpQueryPlayerProtocolDTO request) {
            uid.set(request.uid);
            traceId.set(request.traceId);
        }

        private long uid() {
            return uid.get();
        }

        private String traceId() {
            return traceId.get();
        }
    }

    /**
     * RPG TCP generated dispatcher 示例运行结果。
     *
     * @param protocolId 响应协议号。
     * @param dispatched 是否成功分发到 generated BO。
     * @param uid BO 收到的玩家标识。
     * @param traceId BO 收到的链路标识。
     * @param channel session 中记录的渠道编码。
     * @param requests session 中记录的请求次数。
     * @param handlerThread 业务处理线程名称。
     * @param sessionsClosed 连接关闭后 session 是否已清理。
     * @author zn
     */
    public record TcpDemoResult(
            int protocolId,
            boolean dispatched,
            long uid,
            String traceId,
            String channel,
            long requests,
            String handlerThread,
            boolean sessionsClosed) {

        /**
         * 返回示例运行摘要。
         *
         * @return 摘要行；不可为空；无数据变更；线程安全。
         */
        public String summaryLine() {
            return "rpg-tcp=ok"
                    + "|protocol=" + protocolId
                    + "|dispatched=" + dispatched
                    + "|uid=" + uid
                    + "|trace=" + traceId
                    + "|channel=" + channel
                    + "|requests=" + requests
                    + "|handlerThread=" + handlerThread
                    + "|sessionsClosed=" + sessionsClosed;
        }
    }
}
