package group.zn.zero.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 配置便携性、只读执行和 CLI 机器报告回归。 */
class CodegenProjectConfigTest {
    /** 隔离目录。 */
    @TempDir
    private Path root;

    @Test
    void savedProjectShouldResolvePathsRelativeToConfigAndRoundTrip() throws Exception {
        Path path = root.resolve("codegen.json");
        Map<String, String> settings = Map.of("--input", root.resolve("输入,协议.si").toString(),
                "--out", root.resolve("out").toString(), "--javaDtoSuffix", "", "--genCs", "true");
        CodegenProjectConfig.write(path, settings);
        assertEquals(settings, CodegenProjectConfig.read(path));
        assertFalse(Files.readString(path).contains(root.toString()));
    }

    @Test
    void configRejectsDuplicateUnknownAndOperationalKeys() throws Exception {
        Path path = root.resolve("codegen.json");
        for (String json : new String[]{"{\"out\":\"a\",\"out\":\"b\"}", "{\"unknown\":1}",
                "{\"mode\":\"generate\"}", "{\"input\":[\"a\"],\"out\":\"\"}"}) {
            Files.writeString(path, json);
            assertThrows(IllegalArgumentException.class, () -> CodegenProjectConfig.read(path));
        }
    }

    @Test
    void planAndCheckShouldBeReadOnlyAndCliOverridesConfig() throws Exception {
        Files.writeString(root.resolve("Api.si"), "client_to_server:\n@id(101)\nping(int value)\n");
        Path config = root.resolve("codegen.json");
        Files.writeString(config, "{\"input\":[\"Api.si\"],\"out\":\"generated\",\"languages\":\"typescript\"}");
        assertEquals(0, execute("--config", config.toString(), "--mode", "plan", "--json"));
        assertFalse(Files.exists(root.resolve("generated")));
        assertEquals(3, execute("--config", config.toString(), "--mode", "check", "--json"));
        assertFalse(Files.exists(root.resolve("generated")));
        assertEquals(0, execute("--config", config.toString(), "--json"));
        assertEquals(0, execute("--config", config.toString(), "--mode", "check", "--json"));
        assertEquals(0, execute("--config", config.toString(), "--out", root.resolve("override").toString(), "--json"));
        assertTrue(Files.exists(root.resolve("override")));
        assertEquals(2, execute("--out", "a", "--out", "b", "--json"));
    }

    private int execute(final String... args) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        int code = ProtocolCodegenCli.execute(args, new PrintStream(output, true, StandardCharsets.UTF_8),
                new PrintStream(errors, true, StandardCharsets.UTF_8));
        assertTrue(JsonParser.parseString(output.toString(StandardCharsets.UTF_8)).isJsonObject());
        assertEquals("", errors.toString(StandardCharsets.UTF_8));
        return code;
    }
}
