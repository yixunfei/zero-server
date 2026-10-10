package group.zn.zero.codegen.scaffold;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 首次生成失败不得污染用户工程，输入不能成为模板程序。 @author zn */
class ScaffoldCreationBoundaryTest {
    /** 隔离工程与模板。 */
    @TempDir private Path root;

    @Test
    void missingLateTemplateLeavesNoPartialProject() throws Exception {
        Path template = root.resolve("templates/runtime-scaffold");
        Files.createDirectories(template);
        try (var files = Files.list(Path.of("../templates/runtime-scaffold"))) {
            for (Path file : files.filter(Files::isRegularFile).toList()) Files.copy(file, template.resolve(file.getFileName()));
        }
        Files.delete(template.resolve("NEXT_STEPS.md.tpl"));
        Path output = root.resolve("project");
        assertThrows(IOException.class, () -> new ProjectScaffoldGenerator(ScaffoldCatalog.standard().capabilityModel())
                .generate(request(output, root.resolve("templates"), "0.1.2-SNAPSHOT")));
        assertFalse(Files.exists(output.resolve("pom.xml")));
    }

    @Test
    void versionCannotInjectAnotherPlaceholderOrXml() {
        for (String version : List.of("__CONFIG_DEFAULTS__", "1</version>", "a\nb")) {
            assertThrows(IllegalArgumentException.class, () -> request(root.resolve("out"), Path.of("../templates"), version));
        }
    }

    @Test
    void tokensAreSinglePassAndLineEndingsCanonical() {
        var values = java.util.Map.of("__VALUE__", "__OTHER__", "__OTHER__", "unsafe");
        assertEquals("__OTHER__\n", ScaffoldTemplateTokens.render("__VALUE__\r\n", values));
        assertEquals(ScaffoldTemplateTokens.render("text\n", values), ScaffoldTemplateTokens.render("text\r\n", values));
        assertThrows(IllegalArgumentException.class, () -> ScaffoldTemplateTokens.render("__UNKNOWN__", values));
    }

    @Test
    void commitFailureRollsBackAndAllowsNonForceRetry() throws Exception {
        Path staged = staged();
        Path output = root.resolve("project");
        var request = request(output, Path.of("../templates"), "0.1.2-SNAPSHOT");
        var transaction = new ScaffoldCreationTransaction(index -> {
            if (index == 1) throw new IOException("injected write failure");
        });
        assertThrows(IOException.class, () -> transaction.commit(staged, request));
        assertFalse(Files.exists(output.resolve("a.txt")));
        assertFalse(Files.exists(output.resolve(ScaffoldCreationTransaction.JOURNAL)));
        new ScaffoldCreationTransaction().commit(staged, request);
        assertEquals("a", Files.readString(output.resolve("a.txt")));
        assertEquals("z", Files.readString(output.resolve("z.txt")));
    }

    @Test
    void externalEditDuringFailureKeepsRecoveryMaterials() throws Exception {
        Path staged = staged();
        Path output = root.resolve("project");
        var transaction = new ScaffoldCreationTransaction(index -> {
            if (index == 1) {
                Files.writeString(output.resolve("a.txt"), "external");
                throw new IOException("injected failure");
            }
        });
        IOException failure = assertThrows(IOException.class,
                () -> transaction.commit(staged, request(output, Path.of("../templates"), "0.1.2-SNAPSHOT")));
        assertEquals(1, failure.getSuppressed().length);
        assertEquals("external", Files.readString(output.resolve("a.txt")));
        assertTrue(Files.exists(output.resolve(ScaffoldCreationTransaction.JOURNAL)));
        try (var lock = ScaffoldUpgradeService.acquireLock(output)) {
            assertThrows(IOException.class, () -> ScaffoldCreationTransaction.recoverLocked(output));
        }
    }

    @Test
    void forcePreflightsLateConflictsBeforeWritingAnything() throws Exception {
        Path staged = staged();
        Path output = Files.createDirectory(root.resolve("project"));
        Files.writeString(output.resolve("z.txt"), "user");
        var request = new ProjectScaffoldRequest("sample", "group.zn.sample", output, "0.1.2-SNAPSHOT",
                ScaffoldCatalog.standard().require("runtime"), Path.of("../templates"), "", List.of(), true);
        assertThrows(IllegalStateException.class, () -> new ScaffoldCreationTransaction().commit(staged, request));
        assertFalse(Files.exists(output.resolve("a.txt")));
        assertEquals("user", Files.readString(output.resolve("z.txt")));
    }

    @Test
    void committedCreationRecoveryOnlyCleansJournal() throws Exception {
        Path output = Files.createDirectory(root.resolve("project"));
        Files.writeString(output.resolve("a.txt"), "a");
        Path journal = output.resolve(ScaffoldCreationTransaction.JOURNAL);
        Files.createDirectories(journal.getParent());
        Files.writeString(journal, "{\"schema\":1,\"phase\":\"COMMITTED\","
                + "\"files\":[{\"path\":\"a.txt\",\"content\":\"a\"}]}");
        assertEquals("creation-recovered", new ScaffoldUpgradeService().rollback(output));
        assertEquals("a", Files.readString(output.resolve("a.txt")));
        assertFalse(Files.exists(journal));
    }

    @Test
    void creationAndUpgradeShareExclusiveLock() throws Exception {
        Path staged = staged();
        Path output = root.resolve("project");
        try (var lock = ScaffoldUpgradeService.acquireLock(output)) {
            assertThrows(ScaffoldUpgradeService.ScaffoldUpgradeException.class, () -> new ScaffoldCreationTransaction()
                    .commit(staged, request(output, Path.of("../templates"), "0.1.2-SNAPSHOT")));
            assertFalse(Files.exists(output.resolve("a.txt")));
        }
    }

    @Test
    void templateLineEndingsProduceIdenticalFilesAndManifestHashes() throws Exception {
        Path templates = root.resolve("templates/runtime-scaffold");
        Files.createDirectories(templates);
        try (var files = Files.list(Path.of("../templates/runtime-scaffold"))) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                Files.writeString(templates.resolve(file.getFileName()), Files.readString(file).replace("\r\n", "\n"));
            }
        }
        var generator = new ProjectScaffoldGenerator(ScaffoldCatalog.standard().capabilityModel());
        Path lf = root.resolve("lf");
        Path crlf = root.resolve("crlf");
        generator.generate(request(lf, root.resolve("templates"), "0.1.2-SNAPSHOT"));
        try (var files = Files.list(templates)) {
            for (Path file : files.toList()) Files.writeString(file, Files.readString(file).replace("\n", "\r\n"));
        }
        generator.generate(request(crlf, root.resolve("templates"), "0.1.2-SNAPSHOT"));
        try (var files = Files.walk(lf)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                Path relative = lf.relativize(file);
                if (relative.toString().contains("upgrade.lock")) continue;
                assertEquals(Files.readString(file), Files.readString(crlf.resolve(relative)), relative.toString());
            }
        }
    }

    private Path staged() throws Exception {
        Path staged = Files.createDirectory(root.resolve("staged"));
        Files.writeString(staged.resolve("a.txt"), "a");
        Files.writeString(staged.resolve("z.txt"), "z");
        return staged;
    }

    private ProjectScaffoldRequest request(final Path output, final Path templates, final String version) {
        return new ProjectScaffoldRequest("sample", "group.zn.sample", output, version,
                ScaffoldCatalog.standard().require("runtime"), templates, "", List.of(), false);
    }
}
