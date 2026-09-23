package group.zn.zero.codegen;

import static org.junit.jupiter.api.Assertions.assertThrows;
import group.zn.zero.codegen.dsl.DefaultProtocolDslParser;
import group.zn.zero.codegen.model.CodegenLanguage;
import group.zn.zero.codegen.model.CodegenRequest;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 包名覆盖不能注入文件路径。 @author zn */
class CodegenReportAuditTest {
    /** 临时输出根。 */
    @TempDir private Path output;
    /** slash、反斜杠、绝对路径与关键字均应在生成前拒绝。 */
    @Test void unsafeJavaPackagesAreRejected() {
        var document = new DefaultProtocolDslParser().parse("namespace group.zn.demo\n");
        for (String name : List.of("../escape", "..\\..\\escape", "/escape", "C:\\escape", "group.class")) {
            assertThrows(IllegalArgumentException.class, () -> new CodegenRequest(document, output,
                    List.of(CodegenLanguage.JAVA), true, Map.of(), Map.of(CodegenLanguage.JAVA, name), Map.of()));
        }
    }
    /** DTO 后缀参与文件名，同样不能携带路径。 */
    @Test void unsafeJavaDtoSuffixIsRejected() {
        var document = new DefaultProtocolDslParser().parse("namespace group.zn.demo\n");
        assertThrows(IllegalArgumentException.class, () -> new CodegenRequest(document, output,
                List.of(CodegenLanguage.JAVA), true, Map.of(), Map.of(), Map.of(CodegenLanguage.JAVA, "/../../escape")));
    }

}
