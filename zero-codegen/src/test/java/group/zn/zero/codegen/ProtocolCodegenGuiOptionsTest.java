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
        var options = ProtocolCodegenCli.toCodegenOptions(Map.of("--input", "protocol", "--out", "generated",
                "--outTs", "client/ts"));
        assertEquals(Path.of("client/ts"), options.languageOutputDirs().get(CodegenLanguage.TYPESCRIPT));
        assertEquals(Path.of("generated"), options.languageOutputDirs().get(CodegenLanguage.JAVA));
        assertFalse(options.languageOutputDirs().containsKey(CodegenLanguage.CSHARP));
    }

    /** C# 默认命名空间随基础包名变化，其余语言只使用显式覆盖项。 */
    @Test
    void blankNamespaceOverridesShouldFollowBasePackage() {
        var namespaces = ProtocolCodegenCli.toCodegenOptions(Map.of("--input", "protocol", "--pkg", "game.live.protocol",
                "--gdNs", "custom.gd")).languageNamespaces();
        assertEquals("game.live.protocol", namespaces.get(CodegenLanguage.JAVA));
        assertEquals("Game.Live.Protocol", namespaces.get(CodegenLanguage.CSHARP));
        assertEquals("game.live.protocol", namespaces.get(CodegenLanguage.TYPESCRIPT));
        assertEquals("custom.gd", namespaces.get(CodegenLanguage.GDSCRIPT));
    }
}
