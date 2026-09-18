package group.zn.zero.codegen.scaffold;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import org.junit.jupiter.api.Test;

/** 脚手架安全操作参数与 CLI 输出 focused tests。 */
class ProjectScaffoldCliTest {

    @Test
    void parserRejectsMultipleOperations() {
        var failure = org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> ScaffoldArguments.parse(new String[] {"--plan", "--apply"}));
        assertTrue(failure.getMessage().contains("only one scaffold operation"));
    }

    @Test
    void parserAcceptsAllSafeOperations() {
        for (String operation : new String[] {"--plan", "--diff", "--apply", "--abort", "--rollback", "--migrate"}) {
            var values = ScaffoldArguments.parse(new String[] {operation});
            assertTrue(values.containsKey(operation));
        }
    }

    @Test
    void helpDocumentsSafeUpgradeOperations() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream original = System.out;
        try {
            System.setOut(new PrintStream(output));
            ProjectScaffoldCli.main(new String[] {"--help"});
        } finally {
            System.setOut(original);
        }
        String help = output.toString(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(help.contains("--plan"));
        assertTrue(help.contains("--rollback"));
        assertTrue(help.contains("--migrate"));
        assertEquals(0, help.indexOf("zeroServer local game scaffold generator"));
    }
}
