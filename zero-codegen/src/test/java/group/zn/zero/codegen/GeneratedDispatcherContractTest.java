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
