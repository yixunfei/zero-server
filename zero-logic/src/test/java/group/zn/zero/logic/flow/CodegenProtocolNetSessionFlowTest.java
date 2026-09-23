package group.zn.zero.logic.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.codegen.ProtocolCodegenOptions;
import group.zn.zero.codegen.ProtocolCodegenRunner;
import group.zn.zero.codegen.dsl.ProtocolDslDocument;
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
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * codegen、proto、dispatch、net 与 session 的完整链路测试。
 *
 * @author zn
 */
class CodegenProtocolNetSessionFlowTest {

    /**
     * 临时目录。
     */
    @TempDir
    private Path tempDir;

    /**
     * 验证生成协议可以通过 TCP 进入业务 session 并完成 dispatcher 分发。
     *
     * @throws Exception 生成、编译、网络收发或反射调用失败时抛出。
     */
    @Test
    void generatedProtocolShouldDispatchThroughNetAndLogicSession() throws Exception {
        Path dslRoot = writeDslProject();
        Path generatedRoot = tempDir.resolve("generated");
        List<String> logs = new ArrayList<>();

        ProtocolDslDocument document = new ProtocolCodegenRunner().run(new ProtocolCodegenOptions(
                List.of(dslRoot),
                generatedRoot,
                "group.zn.zero.logic.generated",
                dslRoot.resolve("protoId.txt"),
                false), logs::add);

        Path classesRoot = compileGeneratedSources(generatedRoot);
        try (URLClassLoader loader = new URLClassLoader(
                new URL[] {classesRoot.toUri().toURL()},
                Thread.currentThread().getContextClassLoader())) {
            GeneratedProtocolRuntime generated = loadGeneratedRuntime(loader);
            Object request = newPlayerQueryRequest(generated.requestClass());
            byte[] payload = encodePayload(generated.requestCodecClass(), generated.requestClass(), request);
            ProtocolFrame requestFrame = new ProtocolFrame(
                    generated.queryPlayerProtocolId(),
                    1,
                    0,
                    null,
                    payload);

            FlowResult result = runThroughTcpServer(generated, requestFrame);

            assertEquals(2, document.protocols().size());
            assertTrue(logs.stream().anyMatch(item -> item.contains("Generating protocol sources")));
            assertTrue(Files.exists(generatedRoot.resolve(
                    "group/zn/zero/logic/generated/protocol/dispatch/GeneratedProtocolDispatcher.java")));
            assertEquals("dispatched=true|uid=1001|trace=trace-codegen-net-1|channel=codegen-net|requests=1",
                    result.responseText());
            assertEquals(1001L, result.handledUid());
            assertEquals("trace-codegen-net-1", result.handledTraceId());
            assertEquals("codegen-net", result.channelCode());
            assertEquals(1L, result.sessionRequestCount());
            assertNotNull(result.handlerThreadName());
            assertTrue(result.handlerThreadName().contains("zero-logic-full-flow"));
            assertTrue(result.sessionsClosed());
        }
    }

    private Path writeDslProject() throws IOException {
        Path dslRoot = tempDir.resolve("dsl");
        Files.createDirectories(dslRoot);
        Files.writeString(dslRoot.resolve("Player.si"), """
                # zero-logic full flow test schema.

                client_to_server:
                  queryPlayer(long uid, String traceId); // query player through net.

                server_to_client:
                  queryPlayerResult(long uid, String traceId, String message); // query result.
                """, StandardCharsets.UTF_8);
        Files.writeString(dslRoot.resolve("protoId.txt"), "Player 62000 62100\n", StandardCharsets.UTF_8);
        return dslRoot;
    }

    private Path compileGeneratedSources(final Path sourceRoot) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "JDK compiler is required for generated protocol smoke test");
        Path classesRoot = tempDir.resolve("generated-classes");
        Files.createDirectories(classesRoot);
        List<Path> sources;
        try (Stream<Path> stream = Files.walk(sourceRoot)) {
            sources = stream
                    .filter(path -> path.toString().endsWith(".java"))
                    .toList();
        }
        assertTrue(!sources.isEmpty(), "generated sources must not be empty");
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(
                diagnostics,
                Locale.ROOT,
                StandardCharsets.UTF_8)) {
            fileManager.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(classesRoot));
            Iterable<? extends JavaFileObject> files = fileManager.getJavaFileObjectsFromPaths(sources);
            List<String> options = List.of("-encoding", "UTF-8", "-classpath", runtimeClasspath());
            Boolean success = compiler.getTask(null, fileManager, diagnostics, options, null, files).call();
            assertTrue(Boolean.TRUE.equals(success), diagnostics(diagnostics));
        }
        return classesRoot;
    }

    private String runtimeClasspath() {
        return System.getProperty("java.class.path");
    }

    private String diagnostics(final DiagnosticCollector<JavaFileObject> diagnostics) {
        StringBuilder builder = new StringBuilder("generated source compilation failed");
        for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
            builder.append(System.lineSeparator())
                    .append(diagnostic.getKind())
                    .append(":")
                    .append(diagnostic.getLineNumber())
                    .append(" ")
                    .append(diagnostic.getMessage(Locale.ROOT));
        }
        return builder.toString();
    }

    private GeneratedProtocolRuntime loadGeneratedRuntime(final ClassLoader loader) throws Exception {
        Class<?> dispatcherClass = loader.loadClass(
                "group.zn.zero.logic.generated.protocol.dispatch.GeneratedProtocolDispatcher");
        Class<?> boClass = loader.loadClass("group.zn.zero.logic.generated.bo.PlayerQueryPlayerEventBO");
        Class<?> requestClass = loader.loadClass("group.zn.zero.logic.generated.dto.PlayerQueryPlayerProtocolDTO");
        Class<?> requestCodecClass = loader.loadClass(
                "group.zn.zero.logic.generated.dto.codec.PlayerQueryPlayerProtocolDTOCodec");
        Class<?> protocolIdsClass = loader.loadClass("group.zn.zero.logic.generated.protocol.ProtocolIds");
        int queryPlayerProtocolId = protocolIdsClass.getField("PLAYER_QUERY_PLAYER_PROTOCOL").getInt(null);
        return new GeneratedProtocolRuntime(
                dispatcherClass,
                boClass,
                requestClass,
                requestCodecClass,
                queryPlayerProtocolId);
    }

    private Object newPlayerQueryRequest(final Class<?> requestClass) throws Exception {
        Object request = requestClass.getConstructor().newInstance();
        requestClass.getField("uid").setLong(request, 1001L);
        requestClass.getField("traceId").set(request, "trace-codegen-net-1");
        return request;
    }

    private byte[] encodePayload(
            final Class<?> codecClass,
            final Class<?> requestClass,
            final Object request) throws Exception {
        Object codec = codecClass.getField("INSTANCE").get(null);
        Method write = codecClass.getMethod("write", ZeroWriter.class, requestClass);
        try (ZeroWriter writer = new ZeroWriter()) {
            write.invoke(codec, writer, request);
            return writer.toByteArray();
        }
    }

    private FlowResult runThroughTcpServer(
            final GeneratedProtocolRuntime generated,
            final ProtocolFrame requestFrame) throws Exception {
        ProtocolFrameCodec frameCodec = new ZeroBinaryFrameCodec();
        Object dispatcher = generated.dispatcherClass().getConstructor().newInstance();
        AtomicLong handledUid = new AtomicLong(-1L);
        AtomicReference<String> handledTraceId = new AtomicReference<>();
        Object bo = Proxy.newProxyInstance(
                generated.boClass().getClassLoader(),
                new Class<?>[] {generated.boClass()},
                (proxy, method, args) -> {
                    if ("queryPlayer".equals(method.getName())) {
                        Object request = args[0];
                        handledUid.set(generated.requestClass().getField("uid").getLong(request));
                        handledTraceId.set((String) generated.requestClass().getField("traceId").get(request));
                    }
                    return null;
                });
        generated.dispatcherClass()
                .getMethod("registerPlayerQueryPlayerEventBO", generated.boClass())
                .invoke(dispatcher, bo);
        Method dispatch = generated.dispatcherClass().getMethod("dispatchFrame", ProtocolFrame.class);

        LogicSessionManager sessionManager = new LogicSessionManager();
        CountDownLatch closeLatch = new CountDownLatch(1);
        AtomicReference<String> handlerThreadName = new AtomicReference<>();
        AtomicReference<String> channelCode = new AtomicReference<>();
        AtomicLong requestCount = new AtomicLong(-1L);
        ExecutorService handlerExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "zero-logic-full-flow");
            thread.setDaemon(true);
            return thread;
        });
        ConnectionListener listener = new ConnectionListener() {
            @Override
            public void onClose(final IConnection connection) {
                sessionManager.close(connection);
                closeLatch.countDown();
            }
        };
        ServerFrameHandler handler = (connection, frame) -> {
            handlerThreadName.set(Thread.currentThread().getName());
            LogicSession session = sessionManager.open(connection);
            session.attributes().put(StandardLogicSessionAttributes.CHANNEL_CODE, "codegen-net");
            sessionManager.markRequest(session);
            channelCode.set(session.attributes().get(StandardLogicSessionAttributes.CHANNEL_CODE).orElseThrow());
            requestCount.set(session.attributes().get(StandardLogicSessionAttributes.REQUEST_COUNT).orElseThrow());
            boolean dispatched = dispatchGenerated(dispatch, dispatcher, frame);
            String responseText = "dispatched=" + dispatched
                    + "|uid=" + handledUid.get()
                    + "|trace=" + handledTraceId.get()
                    + "|channel=" + channelCode.get()
                    + "|requests=" + requestCount.get();
            ProtocolFrame response = new ProtocolFrame(
                    frame.protocolId(),
                    frame.protocolVersion(),
                    frame.flags(),
                    frame.extension(),
                    responseText.getBytes(StandardCharsets.UTF_8));
            return CompletableFuture.completedFuture(List.of(response));
        };
        NettyTcpServer server = new NettyTcpServer(
                ServerOptions.tcp("127.0.0.1", 0).withIoThreads(1, 1),
                frameCodec,
                handler,
                listener,
                handlerExecutor);

        try {
            server.start();
            ProtocolFrame response = exchange(server.boundPort(), frameCodec, requestFrame);
            assertTrue(closeLatch.await(Duration.ofSeconds(3).toMillis(), TimeUnit.MILLISECONDS));
            assertTrue(sessionManager.sessions().isEmpty());
            return new FlowResult(
                    new String(response.payload(), StandardCharsets.UTF_8),
                    handledUid.get(),
                    handledTraceId.get(),
                    channelCode.get(),
                    requestCount.get(),
                    handlerThreadName.get(),
                    sessionManager.sessions().isEmpty());
        } finally {
            server.stop();
            handlerExecutor.shutdownNow();
            assertTrue(handlerExecutor.awaitTermination(Duration.ofSeconds(3).toMillis(), TimeUnit.MILLISECONDS));
        }
    }

    private boolean dispatchGenerated(
            final Method dispatch,
            final Object dispatcher,
            final ProtocolFrame frame) {
        try {
            return (boolean) dispatch.invoke(dispatcher, frame);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("generated dispatcher failed", ex);
        }
    }

    private ProtocolFrame exchange(
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
            assertEquals(responseLength, responseBytes.length);
            return frameCodec.decode(responseBytes);
        }
    }

    /**
     * 生成协议运行时反射入口。
     *
     * @param dispatcherClass 分发器类型。
     * @param boClass BO 接口类型。
     * @param requestClass 请求 DTO 类型。
     * @param requestCodecClass 请求 codec 类型。
     * @param queryPlayerProtocolId 查询玩家协议号。
     */
    private record GeneratedProtocolRuntime(
            Class<?> dispatcherClass,
            Class<?> boClass,
            Class<?> requestClass,
            Class<?> requestCodecClass,
            int queryPlayerProtocolId) {
    }

    /**
     * 完整链路结果。
     *
     * @param responseText 响应文本。
     * @param handledUid 业务处理到的玩家标识。
     * @param handledTraceId 业务处理到的 traceId。
     * @param channelCode 会话渠道。
     * @param sessionRequestCount 会话请求数。
     * @param handlerThreadName handler 线程名。
     * @param sessionsClosed 会话是否关闭。
     */
    private record FlowResult(
            String responseText,
            long handledUid,
            String handledTraceId,
            String channelCode,
            long sessionRequestCount,
            String handlerThreadName,
            boolean sessionsClosed) {
    }
}
