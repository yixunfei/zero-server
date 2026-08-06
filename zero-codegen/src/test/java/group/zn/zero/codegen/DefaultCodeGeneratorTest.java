package group.zn.zero.codegen;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.codegen.dsl.DefaultProtocolDslParser;
import group.zn.zero.codegen.dsl.ProtocolDslDocument;
import group.zn.zero.codegen.dsl.SiProtocolProjectParser;
import group.zn.zero.codegen.model.CodegenLanguage;
import group.zn.zero.codegen.model.CodegenRequest;
import group.zn.zero.codegen.model.JavaArtifactKind;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 默认代码生成器测试。
 *
 * @author zn
 */
class DefaultCodeGeneratorTest {

    /**
     * 临时输出目录。
     */
    @TempDir
    private Path tempDir;

    /**
     * 验证 Java 生成链路可以写出首批协议代码。
     *
     * @throws Exception 读取生成文件失败时抛出。
     */
    @Test
    void javaGeneratorShouldWriteProtocolFiles() throws Exception {
        ProtocolDslDocument document = new DefaultProtocolDslParser().parse(sampleDsl());
        new DefaultCodeGenerator().generate(new CodegenRequest(
                document,
                tempDir,
                List.of(CodegenLanguage.JAVA),
                true));

        Path packageDir = tempDir.resolve("group/zn/zero/demo");
        Path dtoDir = packageDir.resolve("dto");
        String requestSource = Files.readString(dtoDir.resolve("LoginRequestDTO.java"), StandardCharsets.UTF_8);
        String idsSource = Files.readString(packageDir.resolve("protocol/ProtocolIds.java"), StandardCharsets.UTF_8);
        String markerSource = Files.readString(dtoDir.resolve("ZeroGeneratedPayload.java"), StandardCharsets.UTF_8);
        String boSource = Files.readString(packageDir.resolve("bo/LoginEventBO.java"), StandardCharsets.UTF_8);
        String implSource = Files.readString(packageDir.resolve("bo/impl/LoginEventBOImp.java"), StandardCharsets.UTF_8);

        assertTrue(markerSource.contains("interface ZeroGeneratedPayload"));
        assertTrue(requestSource.contains("implements Serializable, ZeroGeneratedPayload"));
        assertTrue(requestSource.contains("public int playerId;"));
        assertTrue(requestSource.contains("public String token = null;"));
        assertTrue(requestSource.contains("public List<Map<Integer, String>> attrs = new ArrayList<>();"));
        assertTrue(requestSource.contains("public int[][] matrix = new int[0][];"));
        assertTrue(idsSource.contains("public static final int LOGIN_REQUEST = 1001;"));
        assertTrue(boSource.contains("LoginResponseDTO login(LoginRequestDTO request);"));
        assertTrue(implSource.contains("class LoginEventBOImp implements LoginEventBO"));
    }

    /**
     * 验证 `.si` 项目解析后可以生成 DTO、协议号、BO 和 BOImp。
     *
     * @throws Exception 读取资源或生成文件失败时抛出。
     */
    @Test
    void siProjectShouldGenerateJavaBoFiles() throws Exception {
        Path root = resource("protocol-dsl/sample");
        ProtocolDslDocument document = new SiProtocolProjectParser().parse(
                "group.zn.zero.generated",
                List.of(root),
                root.resolve("protoId.txt"));

        new DefaultCodeGenerator().generate(new CodegenRequest(
                document,
                tempDir,
                List.of(CodegenLanguage.JAVA),
                true));

        Path packageDir = tempDir.resolve("group/zn/zero/generated");
        String dtoSource = Files.readString(
                packageDir.resolve("dto/PlayerQueryPlayerProtocolDTO.java"),
                StandardCharsets.UTF_8);
        String idsSource = Files.readString(packageDir.resolve("protocol/ProtocolIds.java"), StandardCharsets.UTF_8);
        String boSource = Files.readString(
                packageDir.resolve("bo/PlayerQueryPlayerEventBO.java"),
                StandardCharsets.UTF_8);
        String implSource = Files.readString(
                packageDir.resolve("bo/impl/PlayerQueryPlayerEventBOImp.java"),
                StandardCharsets.UTF_8);
        String dispatcherSource = Files.readString(
                packageDir.resolve("protocol/dispatch/GeneratedProtocolDispatcher.java"),
                StandardCharsets.UTF_8);
        String codecSource = Files.readString(
                packageDir.resolve("dto/codec/PlayerQueryPlayerProtocolDTOCodec.java"),
                StandardCharsets.UTF_8);

        assertTrue(dtoSource.contains("public long uid;"));
        assertTrue(dtoSource.contains("public String traceId = \"\";"));
        assertTrue(idsSource.contains("public static final int PLAYER_QUERY_PLAYER_PROTOCOL = 1001;"));
        assertTrue(idsSource.contains("public static final int INVENTORY_QUERY_INVENTORY_PROTOCOL = 3001;"));
        assertTrue(boSource.contains("void queryPlayer(PlayerQueryPlayerProtocolDTO request);"));
        assertTrue(implSource.contains("class PlayerQueryPlayerEventBOImp implements PlayerQueryPlayerEventBO"));
        assertTrue(dispatcherSource.contains("registerPlayerQueryPlayerEventBO"));
        assertTrue(dispatcherSource.contains("PlayerQueryPlayerProtocolDTOCodec.INSTANCE.read"));
        assertTrue(codecSource.contains("implements ZeroPayloadCodec<PlayerQueryPlayerProtocolDTO>"));
    }

    /**
     * 验证多语言客户端生成物可以从同一份 `.si` 工程输出。
     *
     * @throws Exception 读取资源或生成文件失败时抛出。
     */
    @Test
    void siProjectShouldGenerateClientLanguages() throws Exception {
        Path root = resource("protocol-dsl/sample");
        ProtocolDslDocument document = new SiProtocolProjectParser().parse(
                "group.zn.zero.generated",
                List.of(root),
                root.resolve("protoId.txt"));
        Map<CodegenLanguage, Path> outputs = new EnumMap<>(CodegenLanguage.class);
        outputs.put(CodegenLanguage.CSHARP, tempDir.resolve("cs"));
        outputs.put(CodegenLanguage.TYPESCRIPT, tempDir.resolve("ts"));
        outputs.put(CodegenLanguage.GDSCRIPT, tempDir.resolve("gd"));
        Map<CodegenLanguage, String> namespaces = new EnumMap<>(CodegenLanguage.class);
        namespaces.put(CodegenLanguage.CSHARP, "Zero.Generated");

        new DefaultCodeGenerator().generate(new CodegenRequest(
                document,
                tempDir.resolve("java"),
                List.of(CodegenLanguage.CSHARP, CodegenLanguage.TYPESCRIPT, CodegenLanguage.GDSCRIPT),
                false,
                outputs,
                namespaces));

        assertTrue(Files.readString(outputs.get(CodegenLanguage.CSHARP).resolve("ZeroProtocolRuntime.cs"),
                StandardCharsets.UTF_8).contains("public sealed class ZeroWriter"));
        String csharpRuntime = Files.readString(outputs.get(CodegenLanguage.CSHARP).resolve("ZeroProtocolRuntime.cs"),
                StandardCharsets.UTF_8);
        assertTrue(csharpRuntime.contains("public ulong ReadUnsignedLong()"));
        assertTrue(csharpRuntime.contains("public void WriteUnsignedLong(ulong value)"));
        assertTrue(csharpRuntime.contains("interface IZeroGeneratedPayload"));
        assertTrue(Files.readString(outputs.get(CodegenLanguage.CSHARP).resolve("PlayerInfoDTOCodec.cs"),
                StandardCharsets.UTF_8).contains("PlayerItemDTOCodec.Read"));
        assertTrue(Files.readString(outputs.get(CodegenLanguage.CSHARP).resolve("PlayerInfoDTOCodec.cs"),
                StandardCharsets.UTF_8).contains("writer.WriteString(message.title);"));
        assertTrue(Files.readString(outputs.get(CodegenLanguage.CSHARP).resolve("PlayerInfoDTO.cs"),
                StandardCharsets.UTF_8).contains("public List<PlayerItemDTO>[] itemBuckets"));
        assertTrue(Files.readString(outputs.get(CodegenLanguage.CSHARP).resolve("InventoryItemDTO.cs"),
                StandardCharsets.UTF_8).contains("public HashSet<string>"));
        String tsRuntime = Files.readString(outputs.get(CodegenLanguage.TYPESCRIPT).resolve("zero-protocol-runtime.ts"),
                StandardCharsets.UTF_8);
        assertTrue(tsRuntime.contains("interface ZeroGeneratedPayload"));
        assertTrue(tsRuntime.contains("readSet<T>(itemReader: (reader: ZeroReader) => T): Set<T>"));
        assertTrue(tsRuntime.contains("return new Set(values);"));
        assertTrue(Files.readString(outputs.get(CodegenLanguage.TYPESCRIPT).resolve("player-info-dto-codec.ts"),
                StandardCharsets.UTF_8).contains("PlayerItemDTOCodec.read"));
        String gdRuntime = Files.readString(outputs.get(CodegenLanguage.GDSCRIPT).resolve("zero_protocol.gd"),
                StandardCharsets.UTF_8);
        assertTrue(gdRuntime.contains("class ZeroGeneratedPayload"));
        assertTrue(gdRuntime.contains("class PlayerInfoDTO extends ZeroGeneratedPayload"));
        assertTrue(gdRuntime.contains("class PlayerInfoDTOCodec"));
        assertTrue(gdRuntime.contains("peer.big_endian = true"));
        assertTrue(gdRuntime.contains("peer.put_float(value)"));
        assertTrue(gdRuntime.contains("peer.get_double()"));
    }

    /**
     * 验证标准登录、角色、玩家流程示例可以生成四语言协议代码。
     *
     * @throws Exception 读取资源或生成文件失败时抛出。
     */
    @Test
    void standardFlowShouldGenerateAllLanguages() throws Exception {
        Path root = resource("protocol-dsl/standard-flow");
        ProtocolDslDocument document = new SiProtocolProjectParser().parse(
                "group.zn.zero.standard",
                List.of(root),
                root.resolve("protoId.txt"));

        new DefaultCodeGenerator().generate(new CodegenRequest(
                document,
                tempDir,
                List.of(CodegenLanguage.JAVA, CodegenLanguage.CSHARP, CodegenLanguage.TYPESCRIPT,
                        CodegenLanguage.GDSCRIPT),
                true));

        assertTrue(Files.exists(tempDir.resolve("group/zn/zero/standard/dto/AuthLoginProtocolDTO.java")));
        assertTrue(Files.exists(tempDir.resolve("csharp/AuthLoginProtocolDTOCodec.cs")));
        assertTrue(Files.exists(tempDir.resolve("typescript/auth-login-protocol-dto-codec.ts")));
        assertTrue(Files.exists(tempDir.resolve("gdscript/zero_protocol.gd")));
        String csharpSnapshot = Files.readString(tempDir.resolve("csharp/PlayerSnapshotDTO.cs"),
                StandardCharsets.UTF_8);
        assertTrue(csharpSnapshot.contains("public ulong revision { get; set; } = 0UL;"));
        String idsSource = Files.readString(tempDir.resolve("group/zn/zero/standard/protocol/ProtocolIds.java"),
                StandardCharsets.UTF_8);
        assertTrue(idsSource.contains("public static final int AUTH_LOGIN_PROTOCOL = 1001;"));
        assertTrue(idsSource.contains("public static final int ROLE_CREATE_ROLE_PROTOCOL = 3003;"));
        assertTrue(idsSource.contains("public static final int PLAYER_HEARTBEAT_ACK_PROTOCOL = 6002;"));
    }

    /**
     * 验证 DTO 后缀可以按目标语言独立配置，且空字符串会关闭对应语言的后缀。
     *
     * @throws Exception 读取生成文件失败时抛出。
     */
    @Test
    void dtoSuffixShouldBeConfigurablePerLanguage() throws Exception {
        ProtocolDslDocument document = new DefaultProtocolDslParser().parse(sampleDsl());
        Map<CodegenLanguage, String> suffixes = new EnumMap<>(CodegenLanguage.class);
        suffixes.put(CodegenLanguage.JAVA, "");
        suffixes.put(CodegenLanguage.CSHARP, "Packet");

        new DefaultCodeGenerator().generate(new CodegenRequest(
                document,
                tempDir,
                List.of(CodegenLanguage.JAVA, CodegenLanguage.CSHARP),
                false,
                Map.of(),
                Map.of(CodegenLanguage.CSHARP, "Zero.Generated"),
                suffixes));

        assertTrue(Files.exists(tempDir.resolve("group/zn/zero/demo/dto/LoginRequest.java")));
        assertFalse(Files.exists(tempDir.resolve("group/zn/zero/demo/dto/LoginRequestDTO.java")));
        assertTrue(Files.exists(tempDir.resolve("csharp/LoginRequestPacket.cs")));
        assertTrue(Files.exists(tempDir.resolve("csharp/LoginRequestPacketCodec.cs")));
    }

    /**
     * 验证 Java 生成物可以按类型配置独立输出目录和包名。
     *
     * @throws Exception 读取生成文件失败时抛出。
     */
    @Test
    void javaArtifactLayoutShouldBeConfigurablePerKind() throws Exception {
        ProtocolDslDocument document = new DefaultProtocolDslParser().parse(sampleDsl());
        Map<JavaArtifactKind, Path> artifactDirs = new EnumMap<>(JavaArtifactKind.class);
        artifactDirs.put(JavaArtifactKind.DTO, tempDir.resolve("proto"));
        artifactDirs.put(JavaArtifactKind.PROTOCOL, tempDir.resolve("net"));
        artifactDirs.put(JavaArtifactKind.BO, tempDir.resolve("logic"));
        artifactDirs.put(JavaArtifactKind.DISPATCHER, tempDir.resolve("dispatch"));
        Map<JavaArtifactKind, String> artifactPackages = new EnumMap<>(JavaArtifactKind.class);
        artifactPackages.put(JavaArtifactKind.DTO, "group.zn.zero.proto.dto");
        artifactPackages.put(JavaArtifactKind.PROTOCOL, "group.zn.zero.net.protocol");
        artifactPackages.put(JavaArtifactKind.BO, "group.zn.zero.logic.bo");
        artifactPackages.put(JavaArtifactKind.DISPATCHER, "group.zn.zero.net.dispatch");

        new DefaultCodeGenerator().generate(new CodegenRequest(
                document,
                tempDir,
                List.of(CodegenLanguage.JAVA),
                true,
                Map.of(),
                Map.of(),
                Map.of(),
                artifactDirs,
                artifactPackages));

        Path dtoFile = tempDir.resolve("proto/group/zn/zero/proto/dto/LoginRequestDTO.java");
        Path codecFile = tempDir.resolve("proto/group/zn/zero/proto/dto/codec/LoginRequestDTOCodec.java");
        Path idsFile = tempDir.resolve("net/group/zn/zero/net/protocol/ProtocolIds.java");
        Path boFile = tempDir.resolve("logic/group/zn/zero/logic/bo/LoginEventBO.java");
        Path implFile = tempDir.resolve("logic/group/zn/zero/logic/bo/impl/LoginEventBOImp.java");
        Path dispatcherFile = tempDir.resolve("dispatch/group/zn/zero/net/dispatch/GeneratedProtocolDispatcher.java");

        assertTrue(Files.exists(dtoFile));
        assertTrue(Files.exists(codecFile));
        assertTrue(Files.exists(idsFile));
        assertTrue(Files.exists(boFile));
        assertTrue(Files.exists(implFile));
        assertTrue(Files.exists(dispatcherFile));
        String codecSource = Files.readString(codecFile, StandardCharsets.UTF_8);
        String boSource = Files.readString(boFile, StandardCharsets.UTF_8);
        String dispatcherSource = Files.readString(dispatcherFile, StandardCharsets.UTF_8);
        assertTrue(codecSource.contains("package group.zn.zero.proto.dto.codec;"));
        assertTrue(codecSource.contains("import group.zn.zero.proto.dto.LoginRequestDTO;"));
        assertTrue(codecSource.contains("import group.zn.zero.proto.dto.ItemType;"));
        assertTrue(boSource.contains("package group.zn.zero.logic.bo;"));
        assertTrue(boSource.contains("import group.zn.zero.proto.dto.LoginRequestDTO;"));
        assertTrue(boSource.contains("import group.zn.zero.proto.dto.LoginResponseDTO;"));
        assertTrue(dispatcherSource.contains("package group.zn.zero.net.dispatch;"));
        assertTrue(dispatcherSource.contains("import group.zn.zero.logic.bo.LoginEventBO;"));
        assertTrue(dispatcherSource.contains("import group.zn.zero.net.protocol.ProtocolIds;"));
        assertTrue(dispatcherSource.contains("import group.zn.zero.proto.dto.LoginRequestDTO;"));
        assertTrue(dispatcherSource.contains("import group.zn.zero.proto.dto.codec.LoginRequestDTOCodec;"));
    }

    private String sampleDsl() {
        return """
                namespace group.zn.zero.demo

                enum ItemType {
                  UNKNOWN = 0
                  WEAPON = 1
                }

                message LoginRequest {
                  field 1 int playerId
                  field 2 optional<string> token
                  field 3 List<Map<int, string>> attrs
                  field 4 int[][] matrix
                  field 5 ItemType itemType
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
                """;
    }

    private Path resource(final String name) throws URISyntaxException {
        return Path.of(Thread.currentThread().getContextClassLoader().getResource(name).toURI());
    }
}
