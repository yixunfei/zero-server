package group.zn.zero.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import group.zn.zero.codegen.model.CodegenLanguage;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 图形工具的覆盖项与命令行默认派生行为一致。
 *
 * @author zn
 */
class ProtocolCodegenGuiOptionsTest {

    /** 留空语言输出路径代表从总输出目录派生。 */
    @Test
    void blankOutputOverridesShouldBeOmitted() {
        Map<CodegenLanguage, Path> dirs = ProtocolCodegenGui.outputDirs("", " ", "client/ts", "");
        assertEquals(Map.of(CodegenLanguage.TYPESCRIPT, Path.of("client/ts")), dirs);
    }

    /** C# 默认命名空间随基础包名变化，其余语言只使用显式覆盖项。 */
    @Test
    void blankNamespaceOverridesShouldFollowBasePackage() {
        var namespaces = ProtocolCodegenGui.namespaces("game.live.protocol", "", "", "custom.gd");
        assertEquals("game.live.protocol", namespaces.get(CodegenLanguage.JAVA));
        assertEquals("Game.Live.Protocol", namespaces.get(CodegenLanguage.CSHARP));
        assertFalse(namespaces.containsKey(CodegenLanguage.TYPESCRIPT));
        assertEquals("custom.gd", namespaces.get(CodegenLanguage.GDSCRIPT));
    }
}
