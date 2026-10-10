package group.zn.zero.codegen.generator.java;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import group.zn.zero.core.error.ZeroException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 模板单元真实导入表的歧义回归。 @author zn */
class JavaCompilationUnitNamesTest {
    @Test void differentImportsCannotShareSimpleName() {
        assertThrows(ZeroException.class, () -> JavaCompilationUnitNames.validate("dispatcher.java.ftl",
                Map.of("packageName", "sample.dispatcher", "imports", List.of("sample.dto.Box", "sample.bo.Box"))));
    }

    @Test void businessDeclarationCannotShareImportedTypeName() {
        assertThrows(ZeroException.class, () -> JavaCompilationUnitNames.validate("boInterface.java.ftl",
                Map.of("packageName", "sample.bo", "boName", "BoxDTO", "imports", List.of("sample.dto.BoxDTO"))));
        assertThrows(ZeroException.class, () -> JavaCompilationUnitNames.validate("boImpl.java.ftl",
                Map.of("packageName", "sample.impl", "implName", "BoxImp", "imports", List.of("sample.dto.BoxImp"))));
    }

    @Test void repeatedIdenticalImportIsUnambiguous() {
        assertDoesNotThrow(() -> JavaCompilationUnitNames.validate("message.java.ftl",
                Map.of("packageName", "sample.dto", "name", "Box", "imports", List.of("sample.dto.Box", "sample.dto.Box"))));
    }
}
