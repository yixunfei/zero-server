package group.zn.zero.codegen.generator;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import group.zn.zero.codegen.dsl.ProtocolDslDocument;
import group.zn.zero.codegen.dsl.ProtocolDslValidator;
import group.zn.zero.codegen.generator.java.JavaCodegenRenderer;
import group.zn.zero.codegen.model.CodegenLanguage;
import group.zn.zero.codegen.model.CodegenRequest;
import group.zn.zero.codegen.model.ProtocolField;
import group.zn.zero.codegen.model.ProtocolMessage;
import group.zn.zero.codegen.model.ProtocolType;
import group.zn.zero.core.error.ZeroException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 直接模型与公开单语言入口也必须遵守名称和类型预算。 @author zn */
class TargetNamesBoundaryTest {
    /** 临时输出根。 */
    @TempDir private Path root;

    @Test
    void keywordsRespectActualMemberPosition() {
        for (CodegenLanguage language : CodegenLanguage.values()) {
            CodegenRequest bad = request(language, "class");
            if (language == CodegenLanguage.TYPESCRIPT) {
                assertDoesNotThrow(() -> TargetNames.validate(bad, language));
            } else {
                assertThrows(ZeroException.class, () -> TargetNames.validate(bad, language));
            }
        }
        assertDoesNotThrow(() -> TargetNames.validate(request(CodegenLanguage.JAVA, "record"), CodegenLanguage.JAVA));
        assertDoesNotThrow(() -> TargetNames.validate(request(CodegenLanguage.TYPESCRIPT, "type"), CodegenLanguage.TYPESCRIPT));
    }

    @Test
    void samePackageDerivedCodecCollisionFailsEvenAcrossOutputRoots() {
        var document = new ProtocolDslDocument("sample", List.of(), List.of(),
                List.of(new ProtocolMessage("Value", List.of(), ""), new ProtocolMessage("ValueCodec", List.of(), "")), List.of());
        var request = new CodegenRequest(document, root.resolve("out"), List.of(CodegenLanguage.JAVA), false,
                Map.of(), Map.of(), Map.of(CodegenLanguage.JAVA, ""),
                Map.of(group.zn.zero.codegen.model.JavaArtifactKind.CODEC, root.resolve("codecs")),
                Map.of(group.zn.zero.codegen.model.JavaArtifactKind.CODEC, "sample.dto"));
        assertThrows(ZeroException.class, () -> new JavaCodegenRenderer().render(request));
        assertFalse(Files.exists(root.resolve("out")));
    }

    @Test
    void directModelsCannotBypassDepthBudget() {
        ProtocolType type = ProtocolType.scalar("int");
        for (int index = 0; index < 64; index++) type = ProtocolType.array(type);
        var field = new ProtocolField("value", type, 1, false, false, "");
        var document = new ProtocolDslDocument("sample", List.of(), List.of(),
                List.of(new ProtocolMessage("Value", List.of(field), "")), List.of());
        assertThrows(ZeroException.class, () -> ProtocolDslValidator.validate(document));
    }

    @Test
    void singleRendererPreflightsAllFilesBeforeWriting() throws Exception {
        Path output = Files.createDirectories(root.resolve("out/sample/dto/codec"));
        Files.writeString(output.resolve("ValueDTOCodec.java"), "user content");
        assertThrows(ZeroException.class, () -> new JavaCodegenRenderer().render(request(CodegenLanguage.JAVA, "value")));
        assertFalse(Files.exists(output.getParent().resolve("ValueDTO.java")));
        assertFalse(Files.exists(output.getParent().resolve("ZeroGeneratedPayload.java")));
    }

    private CodegenRequest request(final CodegenLanguage language, final String name) {
        var field = new ProtocolField(name, ProtocolType.scalar("int"), 1, false, false, "");
        var document = new ProtocolDslDocument("sample", List.of(), List.of(),
                List.of(new ProtocolMessage("Value", List.of(field), "")), List.of());
        return new CodegenRequest(document, root.resolve("out"), List.of(language));
    }

    /** 模板真实导入的 runtime 类型必须在写文件前拒绝冲突。 */
    @Test void runtimeImportsCannotBeShadowedByEnums() {
        for (String name : List.of("ZeroPayloadCodec", "ZeroException", "ProtocolErrorCode", "java")) {
            var value = new group.zn.zero.codegen.model.ProtocolEnumValue("UNKNOWN", 0, "");
            var item = new group.zn.zero.codegen.model.ProtocolEnum(name, List.of(value), "");
            var document = new ProtocolDslDocument("sample", List.of(), List.of(item), List.of(), List.of());
            var request = new CodegenRequest(document, root.resolve("out"), List.of(CodegenLanguage.JAVA));
            assertThrows(ZeroException.class, () -> new JavaCodegenRenderer().render(request));
            assertFalse(Files.exists(root.resolve("out")));
        }
    }
}
