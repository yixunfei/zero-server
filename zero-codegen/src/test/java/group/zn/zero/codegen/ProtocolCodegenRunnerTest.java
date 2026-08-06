package group.zn.zero.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.codegen.dsl.ProtocolDslDocument;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 协议代码生成执行器测试。
 *
 * @author zn
 */
class ProtocolCodegenRunnerTest {

    /**
     * 临时输出目录。
     */
    @TempDir
    private Path tempDir;

    /**
     * 验证共享执行器可以跑通 `.si` 示例并写出 Java 生成物。
     *
     * @throws Exception 读取资源或生成文件失败时抛出。
     */
    @Test
    void runnerShouldGenerateJavaProject() throws Exception {
        Path root = resource("protocol-dsl/sample");
        List<String> logs = new ArrayList<>();

        ProtocolDslDocument document = new ProtocolCodegenRunner().run(new ProtocolCodegenOptions(
                List.of(root),
                tempDir,
                "group.zn.zero.generated",
                root.resolve("protoId.txt"),
                true), logs::add);

        Path packageDir = tempDir.resolve("group/zn/zero/generated");
        assertEquals(5, document.protocols().size());
        assertTrue(Files.exists(packageDir.resolve("protocol/dispatch/GeneratedProtocolDispatcher.java")));
        assertTrue(Files.exists(packageDir.resolve("bo/impl/PlayerQueryPlayerEventBOImp.java")));
        assertTrue(logs.stream().anyMatch(item -> item.contains("Generating protocol sources")));
    }

    /**
     * 验证工具参数拒绝空输入列表。
     */
    @Test
    void optionsShouldRejectEmptyInputs() {
        assertThrows(IllegalArgumentException.class, () -> new ProtocolCodegenOptions(
                List.of(),
                tempDir,
                "group.zn.zero.generated",
                null,
                false));
    }

    /**
     * 验证 CLI 帮助信息包含 GUI 和 standalone jar 的入口说明。
     */
    @Test
    void cliHelpShouldMentionGuiMode() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int exitCode = ProtocolCodegenCli.execute(
                new String[]{"--help"},
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));

        String output = out.toString(StandardCharsets.UTF_8);
        assertEquals(0, exitCode);
        assertTrue(output.contains("--gui"));
        assertTrue(output.contains("--languages"));
        assertTrue(output.contains("--genCs"));
        assertTrue(output.contains("--genTs"));
        assertTrue(output.contains("--genGd"));
        assertTrue(output.contains("--dtoSuffix"));
        assertTrue(output.contains("--javaDtoSuffix"));
        assertTrue(output.contains("--javaDtoPkg"));
        assertTrue(output.contains("--outJavaDto"));
        assertTrue(output.contains("--javaDispatcherPkg"));
        assertTrue(output.contains("--outJavaDispatcher"));
        assertTrue(output.contains("--csDtoSuffix"));
        assertTrue(output.contains("--tsDtoSuffix"));
        assertTrue(output.contains("--gdDtoSuffix"));
        assertTrue(output.contains("zero-codegen-<version>-all.jar"));
    }

    /**
     * 楠岃瘉 CLI 璇█閫夐」鑳借緭鍑?Java銆丆#銆乀ypeScript 鍜?GDScript 鐢熸垚鐗┿€?     *
     * @throws Exception 璇诲彇璧勬簮鎴栫敓鎴愭枃浠跺け璐ユ椂鎶涘嚭銆?     */
    @Test
    void cliShouldGenerateSelectedClientLanguages() throws Exception {
        Path root = resource("protocol-dsl/sample");
        Path output = tempDir.resolve("cli");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int exitCode = ProtocolCodegenCli.execute(
                new String[]{
                        "--input", root.toString(),
                        "--protoId", root.resolve("protoId.txt").toString(),
                        "--out", output.toString(),
                        "--pkg", "group.zn.zero.generated",
                        "--languages", "java,csharp,typescript,gdscript",
                        "--genBoImpl", "true"
                },
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));

        assertEquals(0, exitCode, err.toString(StandardCharsets.UTF_8));
        assertTrue(Files.exists(output.resolve("group/zn/zero/generated/dto/PlayerQueryPlayerProtocolDTO.java")));
        assertTrue(Files.exists(output.resolve("csharp/PlayerQueryPlayerProtocolDTOCodec.cs")));
        assertTrue(Files.exists(output.resolve("typescript/player-query-player-protocol-dto-codec.ts")));
        assertTrue(Files.exists(output.resolve("gdscript/zero_protocol.gd")));
    }

    /**
     * 验证 CLI 支持按语言配置 DTO 后缀，且空字符串可以关闭指定语言后缀。
     *
     * @throws Exception 读取资源或生成文件失败时抛出。
     */
    @Test
    void cliShouldUsePerLanguageDtoSuffix() throws Exception {
        Path root = resource("protocol-dsl/sample");
        Path output = tempDir.resolve("cli-suffix");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int exitCode = ProtocolCodegenCli.execute(
                new String[]{
                        "--input", root.toString(),
                        "--protoId", root.resolve("protoId.txt").toString(),
                        "--out", output.toString(),
                        "--pkg", "group.zn.zero.generated",
                        "--languages", "java,csharp",
                        "--javaDtoSuffix", "",
                        "--csDtoSuffix", "Packet"
                },
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));

        assertEquals(0, exitCode, err.toString(StandardCharsets.UTF_8));
        assertTrue(Files.exists(output.resolve("group/zn/zero/generated/dto/PlayerQueryPlayerProtocol.java")));
        assertTrue(Files.exists(output.resolve("csharp/PlayerQueryPlayerProtocolPacketCodec.cs")));
    }

    /**
     * 验证 CLI 支持按 Java 生成物类型配置输出目录和包名。
     *
     * @throws Exception 读取资源或生成文件失败时抛出。
     */
    @Test
    void cliShouldUseJavaArtifactLayoutOptions() throws Exception {
        Path root = resource("protocol-dsl/sample");
        Path output = tempDir.resolve("cli-layout");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int exitCode = ProtocolCodegenCli.execute(
                new String[]{
                        "--input", root.toString(),
                        "--protoId", root.resolve("protoId.txt").toString(),
                        "--out", output.toString(),
                        "--pkg", "group.zn.zero.generated",
                        "--languages", "java",
                        "--genBoImpl", "true",
                        "--outJavaDto", output.resolve("proto").toString(),
                        "--outJavaProtocol", output.resolve("net").toString(),
                        "--outJavaBo", output.resolve("logic").toString(),
                        "--outJavaDispatcher", output.resolve("dispatch").toString(),
                        "--javaDtoPkg", "group.zn.zero.proto.dto",
                        "--javaProtocolPkg", "group.zn.zero.net.protocol",
                        "--javaBoPkg", "group.zn.zero.logic.bo",
                        "--javaDispatcherPkg", "group.zn.zero.net.dispatch"
                },
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));

        assertEquals(0, exitCode, err.toString(StandardCharsets.UTF_8));
        Path dtoFile = output.resolve("proto/group/zn/zero/proto/dto/PlayerQueryPlayerProtocolDTO.java");
        Path idsFile = output.resolve("net/group/zn/zero/net/protocol/ProtocolIds.java");
        Path boFile = output.resolve("logic/group/zn/zero/logic/bo/PlayerQueryPlayerEventBO.java");
        Path dispatcherFile = output.resolve("dispatch/group/zn/zero/net/dispatch/GeneratedProtocolDispatcher.java");
        assertTrue(Files.exists(dtoFile));
        assertTrue(Files.exists(idsFile));
        assertTrue(Files.exists(boFile));
        assertTrue(Files.exists(dispatcherFile));
        String dispatcherSource = Files.readString(dispatcherFile, StandardCharsets.UTF_8);
        assertTrue(dispatcherSource.contains("import group.zn.zero.logic.bo.PlayerQueryPlayerEventBO;"));
        assertTrue(dispatcherSource.contains("import group.zn.zero.net.protocol.ProtocolIds;"));
        assertTrue(dispatcherSource.contains("import group.zn.zero.proto.dto.PlayerQueryPlayerProtocolDTO;"));
        assertTrue(dispatcherSource.contains(
                "import group.zn.zero.proto.dto.codec.PlayerQueryPlayerProtocolDTOCodec;"));
    }

    /**
     * 验证 CLI 布尔参数拒绝非 true/false 值，避免静默关闭生成语言。
     */
    @Test
    void cliShouldRejectInvalidBooleanSwitch() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int exitCode = ProtocolCodegenCli.execute(
                new String[]{"--input", tempDir.toString(), "--genCs", "maybe"},
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));

        assertEquals(2, exitCode);
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("argument must be true or false: --genCs"));
    }

    private Path resource(final String name) throws URISyntaxException {
        return Path.of(Thread.currentThread().getContextClassLoader().getResource(name).toURI());
    }
}
