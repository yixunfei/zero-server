package group.zn.zero.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import group.zn.zero.codegen.dsl.ProtocolTypeParser;
import group.zn.zero.core.error.ZeroException;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 校验模式、非法名称及类型预算的真实边界回归。 @author zn */
class CodegenValidationBoundaryTest {
    /** 每个测试独享目录。 */
    @TempDir private Path root;

    @Test
    void validateRejectsManualEditsWithoutWriting() throws Exception {
        Path source = root.resolve("Api.si");
        Files.writeString(source, "client_to_server:\n@id(101)\nping(int value)\n");
        assertEquals(0, execute(source, "validate"));
        assertFalse(Files.exists(root.resolve("out")));
        assertEquals(0, execute(source, "generate"));
        Path dto;
        try (var paths = Files.walk(root.resolve("out"))) {
            dto = paths.filter(path -> path.toString().endsWith("DTO.java")).findFirst().orElseThrow();
        }
        String edit = Files.readString(dto) + "\n// user edit";
        Files.writeString(dto, edit);
        assertEquals(1, execute(source, "validate"));
        assertEquals(edit, Files.readString(dto));
    }

    @Test
    void illegalNamesFailBeforeCreatingOutputs() throws Exception {
        Path source = root.resolve("Api.si");
        Files.writeString(source, "client_to_server:\n@id(101)\n1login(int class)\n");
        assertEquals(1, execute(source, "generate"));
        assertFalse(Files.exists(root.resolve("out")));
    }

    @Test
    void recursiveAndIterativeTypeWrappersShareBudget() {
        var parser = new ProtocolTypeParser(Set.of(), Set.of());
        assertThrows(ZeroException.class, () -> parser.parse("nullable ".repeat(10000) + "string"));
        assertThrows(ZeroException.class, () -> parser.parse("string" + "[]".repeat(64)));
        parser.parse("string" + "[]".repeat(63));
    }

    private int execute(final Path source, final String mode) {
        var bytes = new ByteArrayOutputStream();
        var stream = new PrintStream(bytes);
        return ProtocolCodegenCli.execute(new String[]{"--input", source.toString(), "--out",
                root.resolve("out").toString(), "--mode", mode, "--json"}, stream, stream);
    }

    /** 合法的 Class 枚举不得遮蔽 codec 的 java.lang.Class 返回类型。 */
    @Test
    void classEnumGeneratesCompilableJava() throws Exception {
        Path source = root.resolve("Api.si");
        Files.writeString(source, "enum Class {\n UNKNOWN = 0\n}\nstruct Box {\n Class type;\n}\n"
                + "client_to_server:\n@id(101)\nputBox(Box value)\n");
        assertEquals(0, execute(source, "validate"));
        assertEquals(0, execute(source, "generate"));
        compileGeneratedJava();
    }

    /** JDK 注解及异常类也不能被用户枚举遮蔽。 */
    @Test
    void javaLangNamesDoNotShadowTemplateInfrastructure() throws Exception {
        Path source = root.resolve("Api.si");
        Files.writeString(source, "enum Override {\n UNKNOWN = 0\n}\n"
                + "enum SuppressWarnings {\n UNKNOWN = 0\n}\n"
                + "enum IllegalArgumentException {\n UNKNOWN = 0\n}\n"
                + "enum UnsupportedOperationException {\n UNKNOWN = 0\n}\n"
                + "struct Box {\n Override annotation;\n SuppressWarnings warning;\n"
                + " IllegalArgumentException argument;\n UnsupportedOperationException operation;\n}\n"
                + "client_to_server:\n@id(101)\nputBox(Box value)\n");
        assertEquals(0, execute(source, "generate"));
        compileGeneratedJava();
    }

    private void compileGeneratedJava() throws Exception {
        Path classes = Files.createDirectories(root.resolve("classes"));
        var arguments = new java.util.ArrayList<String>(java.util.List.of("--release", "21", "-proc:none",
                "-classpath", System.getProperty("java.class.path"), "-d", classes.toString()));
        try (var paths = Files.walk(root.resolve("out"))) {
            paths.filter(path -> path.toString().endsWith(".java")).map(Path::toString).forEach(arguments::add);
        }
        var diagnostics = new ByteArrayOutputStream();
        assertEquals(0, javax.tools.ToolProvider.getSystemJavaCompiler().run(null, diagnostics, diagnostics,
                arguments.toArray(String[]::new)), diagnostics.toString(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** 实际导入的枚举不能与本生成单元的 codec 声明同名。 */
    @Test
    void derivedCodecImportCollisionFailsBeforeWriting() throws Exception {
        rejectEnumCollision("BoxDTOCodec");
    }

    /** 类型不能占用其他生成物所需的包路径。 */
    @Test
    void typePackageCollisionFailsBeforeWriting() throws Exception {
        rejectEnumCollision("codec");
    }

    /** 用户类型与模板基础设施两条实际 import 不得共享简单名。 */
    @Test
    void enumImportCannotCollideWithRuntimeImport() throws Exception {
        rejectEnumCollision("ZeroReader");
    }

    private void rejectEnumCollision(final String name) throws Exception {
        Path source = root.resolve("Api.si");
        Files.writeString(source, "enum " + name + " {\n UNKNOWN = 0\n}\nstruct Box {\n " + name
                + " kind;\n}\nclient_to_server:\n@id(101)\nputBox(Box value)\n");
        assertEquals(1, execute(source, "validate"));
        assertFalse(Files.exists(root.resolve("out")));
        assertEquals(1, execute(source, "generate"));
        assertFalse(Files.exists(root.resolve("out")));
    }
}
