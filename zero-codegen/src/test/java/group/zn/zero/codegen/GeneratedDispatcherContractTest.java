package group.zn.zero.codegen;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.codegen.dsl.SiProtocolProjectParser;
import group.zn.zero.codegen.model.CodegenLanguage;
import group.zn.zero.codegen.model.CodegenRequest;
import group.zn.zero.codegen.model.JavaArtifactKind;
import group.zn.zero.core.error.ZeroException;
import group.zn.zero.protocol.ProtocolFrame;
import group.zn.zero.protocol.buffer.ZeroReader;
import group.zn.zero.protocol.buffer.ZeroWriter;
import group.zn.zero.protocol.codec.ZeroPayloadCodec;
import group.zn.zero.protocol.error.ProtocolErrorCode;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 编译并执行真实生成物，验证只读分发、输入完整性及输出布局契约。
 *
 * @author zn
 */
class GeneratedDispatcherContractTest {

    /** 临时协议、生成源码与编译输出。 */
    @TempDir private Path root;

    /** 默认布局可编译且严格分发；测试独占生成目录。 */
    @Test
    void defaultLayoutShouldDispatchOwnedMessages() throws Exception {
        verifyGeneratedDispatcher(false);
    }

    /** 自定义各输出目录、包名和 DTO 后缀时保持相同运行行为。 */
    @Test
    void splitLayoutAndCustomSuffixShouldDispatchOwnedMessages() throws Exception {
        verifyGeneratedDispatcher(true);
    }

    /** 显式响应声明在 DTO 和展开参数两种签名下都能编译并返回实际异步响应。 */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @SuppressWarnings("unchecked")
    void explicitResponseShouldCompileAndComplete(final boolean expanded) throws Exception {
        var parsed = new group.zn.zero.codegen.dsl.DefaultProtocolDslParser().parse("""
                namespace group.zn.zero.generated
                message LoginRequest {
                  field 1 long uid
                  field 2 string token
                }
                message LoginResponse {
                  field 1 boolean ok
                }
                protocol LoginRequest {
                  id 1001
                  direction client_to_server
                }
                method login {
                  protocol LoginRequest
                  request LoginRequest
                  response LoginResponse
                  bo LoginEventBO
                }
                """);
        var method = parsed.methods().getFirst();
        var document = new group.zn.zero.codegen.dsl.ProtocolDslDocument(parsed.namespace(), parsed.protocols(),
                parsed.enums(), parsed.messages(), List.of(new group.zn.zero.codegen.model.ProtocolMethod(
                        method.definition(), method.requestMessage(), method.responseMessage(), method.boName(),
                        method.methodName(), method.comment(), parsed.messages().getFirst().fields(), expanded)));
        var request = new CodegenRequest(document, root.resolve("sources"), List.of(CodegenLanguage.JAVA), true);
        new DefaultCodeGenerator().generate(request);
        try (var loader = compile()) {
            var requestType = loader.loadClass(request.javaPackage(JavaArtifactKind.DTO) + ".LoginRequestDTO");
            var responseType = loader.loadClass(request.javaPackage(JavaArtifactKind.DTO) + ".LoginResponseDTO");
            var async = loader.loadClass(request.javaPackage(JavaArtifactKind.BO) + ".LoginEventBO$Async");
            var dispatcher = loader.loadClass(request.javaPackage(JavaArtifactKind.DISPATCHER) + ".GeneratedProtocolDispatcher");
            var pending = new java.util.concurrent.CompletableFuture<Object>();
            Object bo = Proxy.newProxyInstance(loader, new Class<?>[] {async}, (proxy, invoked, args) -> {
                assertEquals(1001L, expanded ? args[0] : requestType.getField("uid").get(args[0]));
                assertEquals("token", expanded ? args[1] : requestType.getField("token").get(args[0]));
                return pending;
            });
            var requestCodec = codec(loader, request, "LoginRequestDTO");
            var responseCodec = codec(loader, request, "LoginResponseDTO");
            var endpoint = (group.zn.zero.protocol.AsyncProtocolEndpoint<Object, Object>) dispatcher.getMethod(
                    "endpointLoginEventBOlogin", async, int.class, ZeroPayloadCodec.class).invoke(null, bo, 1002, responseCodec);
            Object input = requestType.getConstructor().newInstance();
            requestType.getField("uid").setLong(input, 1001L);
            requestType.getField("token").set(input, "token");
            var delivered = endpoint.handle(new ProtocolFrame(1001, 1, 0, null, encode(requestCodec, input))).toCompletableFuture();
            assertFalse(delivered.isDone());
            Object output = responseType.getConstructor().newInstance();
            responseType.getField("ok").setBoolean(output, true);
            pending.complete(output);
            assertEquals(1002, delivered.join().protocolId());
            assertArrayEquals(encode(responseCodec, output), delivered.join().payload());
        }
    }

    private void verifyGeneratedDispatcher(final boolean split) throws Exception {
        CodegenRequest request = generate(split);
        try (URLClassLoader loader = compile()) {
            String dtoName = "PlayerQueryProtocol" + request.dtoSuffix(CodegenLanguage.JAVA);
            Class<?> dto = loader.loadClass(request.javaPackage(JavaArtifactKind.DTO) + "." + dtoName);
            Class<?> bo = loader.loadClass(request.javaPackage(JavaArtifactKind.BO) + ".PlayerQueryEventBO");
            Class<?> dispatcherType = loader.loadClass(request.javaPackage(JavaArtifactKind.DISPATCHER)
                    + ".GeneratedProtocolDispatcher");
            Object dispatcher = dispatcherType.getConstructor().newInstance();
            int id = request.document().protocols().getFirst().id();
            Method bytes = dispatcherType.getMethod("dispatch", int.class, byte[].class);
            Method frame = dispatcherType.getMethod("dispatchFrame", ProtocolFrame.class);
            Method register = dispatcherType.getMethod("registerPlayerQueryEventBO", bo);
            assertEquals(false, bytes.invoke(dispatcher, id, new byte[0]));
            assertEquals(false, frame.invoke(dispatcher, new ProtocolFrame(id, 1, 0, null, new byte[0])));
            List<Object> received = new ArrayList<>();
            register.invoke(dispatcher, business(bo, received));
            ZeroPayloadCodec<Object> codec = codec(loader, request, dtoName);
            byte[] payload = encode(codec, newRequest(dto));
            verifyAsync(loader, request, dispatcherType, codec, payload, id);
            ProtocolFrame ownedFrame = new ProtocolFrame(id, 1, 0, null, payload);
            assertEquals(true, bytes.invoke(dispatcher, id, payload));
            assertEquals(true, frame.invoke(dispatcher, ownedFrame));
            Arrays.fill(payload, (byte) 0);
            assertEquals(true, frame.invoke(dispatcher, ownedFrame));
            assertEquals(3, received.size());
            for (Object message : received) {
                assertEquals(1001L, dto.getField("uid").get(message));
                assertEquals("玩家😀", dto.getField("traceId").get(message));
                assertEquals(List.of("甲", ""), dto.getField("tags").get(message));
                assertArrayEquals(new byte[] {1, 2, 3}, (byte[]) dto.getField("data").get(message));
            }
            byte[] original = ownedFrame.payload();
            ((byte[]) dto.getField("data").get(received.get(1)))[0] = 9;
            assertArrayEquals(original, ownedFrame.payload());
            verifyRejectedPayloads(dispatcher, bytes, frame, id, original, received);
            assertEquals(true, bytes.invoke(dispatcher, id, withExtension(original)));
            assertEquals(4, received.size());
            List<Object> replacement = new ArrayList<>();
            register.invoke(dispatcher, business(bo, replacement));
            assertEquals(true, frame.invoke(dispatcher, ownedFrame));
            assertEquals(1, replacement.size());
            assertEquals(4, received.size());
            assertEquals(false, bytes.invoke(dispatcher, Integer.MAX_VALUE, new byte[0]));
            assertEquals(false, frame.invoke(dispatcher,
                    new ProtocolFrame(Integer.MAX_VALUE, 1, 0, null, new byte[0])));
            assertInstanceOf(NullPointerException.class, assertThrows(InvocationTargetException.class,
                    () -> frame.invoke(dispatcher, new Object[] {null})).getCause());
            assertInstanceOf(NullPointerException.class, assertThrows(InvocationTargetException.class,
                    () -> bytes.invoke(dispatcher, id, null)).getCause());
        }
    }

    @SuppressWarnings("unchecked")
    private void verifyAsync(final ClassLoader loader, final CodegenRequest request, final Class<?> dispatcherType,
            final ZeroPayloadCodec<Object> codec, final byte[] payload, final int id) throws Exception {
        Class<?> async = loader.loadClass(request.javaPackage(JavaArtifactKind.BO) + ".PlayerQueryEventBO$Async");
        var pending = new java.util.concurrent.CompletableFuture<Object>();
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var argument = new java.util.concurrent.atomic.AtomicReference<Object>();
        Object bo = Proxy.newProxyInstance(loader, new Class<?>[]{async}, (proxy, method, args) -> {
            calls.incrementAndGet(); argument.set(args[0]); return pending;
        });
        var endpoint = (group.zn.zero.protocol.AsyncProtocolEndpoint<Object, Object>) dispatcherType.getMethod(
                "endpointPlayerQueryEventBOquery", async, int.class, ZeroPayloadCodec.class).invoke(null, bo, id + 1, codec);
        var response = endpoint.handle(new ProtocolFrame(id, 1, 0, null, payload)).toCompletableFuture();
        assertFalse(response.isDone(), "typed response must await business completion");
        var malformed = endpoint.handle(new ProtocolFrame(id, 1, 0, null,
                Arrays.copyOf(payload, payload.length + 1))).toCompletableFuture().join();
        assertTrue(group.zn.zero.protocol.ProtocolFeature.ERROR.enabledIn(malformed.flags()));
        assertEquals(1, calls.get(), "reject trailing bytes before side effects");
        pending.complete(argument.get());
        var frame = response.join();
        assertEquals(id + 1, frame.protocolId());
        assertArrayEquals(payload, frame.payload());
        var failed = new java.util.concurrent.CompletableFuture<Object>();
        Object failureBo = Proxy.newProxyInstance(loader, new Class<?>[]{async}, (proxy, method, args) -> failed);
        Object dispatcher = dispatcherType.getConstructor().newInstance();
        dispatcherType.getMethod("registerPlayerQueryEventBOAsync", async).invoke(dispatcher, failureBo);
        var delivered = (java.util.concurrent.CompletionStage<Object>) dispatcherType.getMethod(
                "dispatchAsync", int.class, byte[].class).invoke(dispatcher, id, payload);
        assertFalse(delivered.toCompletableFuture().isDone());
        var cause = ZeroException.of(ProtocolErrorCode.DECODE_FAILED);
        failed.completeExceptionally(cause);
        assertEquals(cause, assertThrows(java.util.concurrent.CompletionException.class,
                () -> delivered.toCompletableFuture().join()).getCause());
    }

    private void verifyRejectedPayloads(final Object dispatcher, final Method bytes, final Method frame,
            final int id, final byte[] payload, final List<Object> received) {
        List<byte[]> invalid = List.of(new byte[0], Arrays.copyOf(payload, payload.length - 1),
                Arrays.copyOf(payload, payload.length + 1));
        for (byte[] input : invalid) {
            ZeroException arrayError = assertInstanceOf(ZeroException.class,
                    assertThrows(InvocationTargetException.class,
                            () -> bytes.invoke(dispatcher, id, input)).getCause());
            ZeroException frameError = assertInstanceOf(ZeroException.class,
                    assertThrows(InvocationTargetException.class,
                            () -> frame.invoke(dispatcher, new ProtocolFrame(id, 1, 0, null, input))).getCause());
            assertEquals(ProtocolErrorCode.DECODE_FAILED.code(), arrayError.code());
            assertEquals(arrayError.code(), frameError.code());
            assertEquals(3, received.size(), "invalid input must not execute business code");
        }
    }

    private CodegenRequest generate(final boolean split) throws Exception {
        Path dsl = root.resolve("Player.si");
        Files.writeString(dsl, """
                client_to_server:
                  query(long uid, String traceId, List<String> tags, byte[] data);
                """);
        var document = new SiProtocolProjectParser().parse("group.zn.zero.generated", List.of(dsl), null);
        Map<JavaArtifactKind, Path> directories = new EnumMap<>(JavaArtifactKind.class);
        Map<JavaArtifactKind, String> packages = new EnumMap<>(JavaArtifactKind.class);
        if (split) {
            for (JavaArtifactKind kind : JavaArtifactKind.values()) {
                String name = kind.name().toLowerCase(Locale.ROOT);
                directories.put(kind, root.resolve(name));
                packages.put(kind, "group.zn.zero.generated.custom." + name);
            }
        }
        CodegenRequest request = new CodegenRequest(document, root.resolve("sources"),
                List.of(CodegenLanguage.JAVA), true, Map.of(), Map.of(),
                Map.of(CodegenLanguage.JAVA, split ? "Packet" : "DTO"), directories, packages);
        new DefaultCodeGenerator().generate(request);
        return request;
    }

    private URLClassLoader compile() throws Exception {
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "generated source tests require a JDK");
        List<Path> sources;
        try (Stream<Path> files = Files.walk(root)) {
            sources = files.filter(path -> path.toString().endsWith(".java")).toList();
        }
        assertFalse(sources.isEmpty());
        Path classes = Files.createDirectories(root.resolve("classes"));
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (var manager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            manager.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(classes));
            assertTrue(compiler.getTask(null, manager, diagnostics,
                    List.of("--release", "21", "-classpath", System.getProperty("java.class.path")),
                    null, manager.getJavaFileObjectsFromPaths(sources)).call(), diagnostics.getDiagnostics().toString());
        }
        return new URLClassLoader(new URL[] {classes.toUri().toURL()}, getClass().getClassLoader());
    }

    private Object business(final Class<?> bo, final List<Object> received) {
        return Proxy.newProxyInstance(bo.getClassLoader(), new Class<?>[] {bo}, (proxy, method, args) -> {
            if ("query".equals(method.getName())) {
                received.add(args[0]);
            }
            return null;
        });
    }

    @SuppressWarnings("unchecked")
    private ZeroPayloadCodec<Object> codec(final ClassLoader loader, final CodegenRequest request,
            final String dtoName) throws Exception {
        return (ZeroPayloadCodec<Object>) loader.loadClass(request.javaPackage(JavaArtifactKind.CODEC)
                + "." + dtoName + "Codec").getField("INSTANCE").get(null);
    }

    private Object newRequest(final Class<?> dto) throws Exception {
        Object message = dto.getConstructor().newInstance();
        dto.getField("uid").setLong(message, 1001L);
        dto.getField("traceId").set(message, "玩家😀");
        dto.getField("tags").set(message, List.of("甲", ""));
        dto.getField("data").set(message, new byte[] {1, 2, 3});
        return message;
    }

    private byte[] encode(final ZeroPayloadCodec<Object> codec, final Object message) {
        try (ZeroWriter writer = new ZeroWriter()) {
            codec.write(writer, message);
            return writer.toByteArray();
        }
    }

    private byte[] withExtension(final byte[] payload) {
        ZeroReader reader = ZeroReader.readOnly(payload);
        int end = reader.beginObject();
        int start = reader.readerIndex();
        try (ZeroWriter writer = new ZeroWriter()) {
            int marker = writer.beginObject();
            for (int index = start; index < end; index++) {
                writer.writeByte(payload[index]);
            }
            writer.writeString("future field");
            writer.endObject(marker);
            return writer.toByteArray();
        }
    }
}
