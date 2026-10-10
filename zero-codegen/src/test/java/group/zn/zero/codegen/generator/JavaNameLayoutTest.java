package group.zn.zero.codegen.generator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import group.zn.zero.codegen.dsl.ProtocolDslDocument;
import group.zn.zero.codegen.error.CodegenErrorCode;
import group.zn.zero.codegen.generator.java.JavaCodegenRenderer;
import group.zn.zero.codegen.model.CodegenLanguage;
import group.zn.zero.codegen.model.CodegenRequest;
import group.zn.zero.codegen.model.JavaArtifactKind;
import group.zn.zero.codegen.model.ProtocolEnum;
import group.zn.zero.codegen.model.ProtocolEnumValue;
import group.zn.zero.codegen.model.ProtocolField;
import group.zn.zero.codegen.model.ProtocolMessage;
import group.zn.zero.codegen.model.ProtocolType;
import group.zn.zero.core.error.ZeroException;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 自定义包、目录、后缀及合法跨包同名的真实编译回归。 @author zn */
class JavaNameLayoutTest {
    /** 测试独享输出。 */
    @TempDir private Path root;

    @Test void customSuffixImportCollisionFailsWithoutTouchingExistingFiles() throws Exception {
        var request = request("BoxWireCodec", true, "Wire", "custom.encoding");
        Files.createDirectories(root.resolve("out"));
        Path marker = root.resolve("out/manual.txt");
        Files.writeString(marker, "keep");
        rejected(request);
        assertEquals("keep", Files.readString(marker));
        assertFalse(Files.exists(root.resolve("codecs")));
    }

    @Test void typeClashesWithCustomPackageAncestorAcrossOutputRoots() {
        rejected(request("branch", false, "DTO", "sample.dto.branch.deep"));
        assertFalse(Files.exists(root.resolve("out")));
        assertFalse(Files.exists(root.resolve("codecs")));
    }

    @Test void unrelatedCrossPackageSameNameCompiles() throws Exception {
        new JavaCodegenRenderer().render(request("BoxDTOCodec", false, "DTO", "sample.dto.codec"));
        compile();
    }

    @Test void codecEnumCompilesWhenPackageMoves() throws Exception {
        new JavaCodegenRenderer().render(request("codec", true, "Wire", "custom.encoding"));
        compile();
    }

    @Test void runtimeImportCannotHideSamePackageEnumReference() {
        rejected(request("ZeroReader", true, "DTO", "sample.dto"));
    }

    private void rejected(final CodegenRequest request) {
        var failure = assertThrows(ZeroException.class, () -> new JavaCodegenRenderer().render(request));
        assertEquals(CodegenErrorCode.DSL_VALIDATION_FAILED, failure.errorCode());
    }

    private CodegenRequest request(final String enumName, final boolean referenced,
            final String suffix, final String codecPackage) {
        var item = new ProtocolEnum(enumName, List.of(new ProtocolEnumValue("UNKNOWN", 0, "")), "");
        var field = new ProtocolField("kind", ProtocolType.enumType(enumName), 1, false, false, "");
        var message = new ProtocolMessage("Box", referenced ? List.of(field) : List.of(), "");
        var document = new ProtocolDslDocument("sample", List.of(), List.of(item), List.of(message), List.of());
        return new CodegenRequest(document, root.resolve("out"), List.of(CodegenLanguage.JAVA), false,
                Map.of(), Map.of(), Map.of(CodegenLanguage.JAVA, suffix),
                Map.of(JavaArtifactKind.CODEC, root.resolve("codecs")), Map.of(JavaArtifactKind.CODEC, codecPackage));
    }

    private void compile() throws Exception {
        Path classes = Files.createDirectories(root.resolve("classes"));
        var args = new ArrayList<>(List.of("--release", "21", "-proc:none", "-classpath",
                System.getProperty("java.class.path"), "-d", classes.toString()));
        try (var paths = Files.walk(root)) {
            paths.filter(path -> path.toString().endsWith(".java")).map(Path::toString).forEach(args::add);
        }
        var diagnostics = new ByteArrayOutputStream();
        assertEquals(0, javax.tools.ToolProvider.getSystemJavaCompiler().run(null, diagnostics, diagnostics,
                args.toArray(String[]::new)), diagnostics.toString(java.nio.charset.StandardCharsets.UTF_8));
    }
}
