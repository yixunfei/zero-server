package group.zn.zero.codegen.scaffold;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 脚手架 ownership 三路比较与事务恢复 focused tests。 */
class ScaffoldUpgradeServiceTest {

    @TempDir
    private Path temporary;

    @Test
    void modifiedGeneratedFileMustBeRejectedWithoutChangingBytes() throws Exception {
        Path project = generate("conflict");
        Path application = project.resolve("src/main/java/group/zn/sample/game/SampleGameApplication.java");
        String original = Files.readString(application, StandardCharsets.UTF_8);
        Files.writeString(application, original + "\n// user change\n", StandardCharsets.UTF_8);
        String changed = Files.readString(application, StandardCharsets.UTF_8);

        ScaffoldUpgradeService service = new ScaffoldUpgradeService();
        ScaffoldUpgradeService.ScaffoldChangePlan plan = service.plan(request(project));

        assertTrue(plan.hasConflicts());
        assertTrue(plan.changes().stream().anyMatch(change -> change.status().equals("conflict")));
        ScaffoldUpgradeService.ScaffoldUpgradeException failure = assertThrows(
                ScaffoldUpgradeService.ScaffoldUpgradeException.class, () -> service.apply(request(project)));
        assertTrue(failure.getMessage().startsWith("SCAFFOLD-CONFLICT|"));
        assertEquals(changed, Files.readString(application, StandardCharsets.UTF_8));
        assertFalse(Files.exists(project.resolve(".zero/scaffold/LATEST")));
    }

    @Test
    void unchangedProjectProducesAnEmptyPlanAndApplyIsIdempotent() throws Exception {
        Path project = generate("idempotent");
        ScaffoldUpgradeService service = new ScaffoldUpgradeService();

        ScaffoldUpgradeService.ScaffoldChangePlan plan = service.plan(request(project));

        assertFalse(plan.hasConflicts());
        assertTrue(plan.changes().isEmpty(), plan.summary());
        ScaffoldUpgradeService.ScaffoldTransaction transaction = service.apply(request(project));
        assertEquals("COMMITTED", transaction.state());
        assertTrue(Files.isRegularFile(transaction.directory().resolve("state")));
    }

    @Test
    void explicitMigrationUpgradesLegacyManifestWithoutChangingGeneratedFiles() throws Exception {
        Path project = generate("migration");
        Path manifest = project.resolve("zero-scaffold.json");
        String original = Files.readString(manifest, StandardCharsets.UTF_8);
        String legacy = original.replace("\"ownershipSchemaVersion\": 1", "\"schemaVersion\": 1")
                .replace(", \"ownershipSchemaVersion\": 1", "");
        Files.writeString(manifest, legacy, StandardCharsets.UTF_8);
        String application = Files.readString(
                project.resolve("src/main/java/group/zn/sample/game/SampleGameApplication.java"), StandardCharsets.UTF_8);

        String migrated = new ScaffoldUpgradeService().migrate(project);

        assertTrue(migrated.contains("\"ownershipSchemaVersion\": 1"));
        assertEquals(application, Files.readString(
                project.resolve("src/main/java/group/zn/sample/game/SampleGameApplication.java"), StandardCharsets.UTF_8));
    }

    @Test
    void rollbackRestoresPreviousBytesAndIsSafeToRepeat() throws Exception {
        Path project = generate("rollback");
        Path application = project.resolve(
                "src/main/java/group/zn/sample/game/SampleGameApplication.java");
        String before = Files.readString(application, StandardCharsets.UTF_8);
        ScaffoldUpgradeService service = new ScaffoldUpgradeService();
        ProjectScaffoldRequest upgraded = new ProjectScaffoldRequest("sample-game", "group.zn.sample.game", project,
                "0.1.0-next", ScaffoldCatalog.standard().require("local"), Path.of("../templates"), "", List.of(), false);

        ScaffoldUpgradeService.ScaffoldTransaction transaction = service.apply(upgraded);
        assertEquals("COMMITTED", transaction.state());
        String id = service.rollback(project);
        assertEquals(transaction.id(), id);
        assertEquals(before, Files.readString(application, StandardCharsets.UTF_8));
        assertEquals(id, service.rollback(project));
    }

    @Test
    void malformedOwnershipFieldsRequireMigration() {
        String content = "{\n  \"ownershipSchemaVersion\": 1,\n  \"files\": [\n"
                + "    {\"path\": \"src/App.java\", \"owner\": \"attacker\", \"template\": \"\", \"sha256\": \"bad\"}\n"
                + "  ]\n}\n";

        ScaffoldOwnershipManifest manifest = ScaffoldOwnershipManifest.parse(content);

        assertFalse(manifest.isUsable());
        assertTrue(manifest.migrationReason().isPresent());
        assertFalse(manifest.rejectedEntries().isEmpty());
    }

    @Test
    void missingGeneratedFileIsReportedAndBlocksApply() throws Exception {
        Path project = generate("missing-generated");
        Path application = project.resolve("src/main/java/group/zn/sample/game/SampleGameApplication.java");
        Files.delete(application);

        ScaffoldUpgradeService.ScaffoldChangePlan plan = new ScaffoldUpgradeService().plan(request(project));

        assertTrue(plan.changes().stream().anyMatch(change -> change.path().endsWith("SampleGameApplication.java")
                && change.status().equals("conflict")));
        assertTrue(plan.hasConflicts());
    }

    @Test
    void legacyManifestWithoutOwnershipBaselineRequiresMigration() throws Exception {
        Path project = generate("legacy");
        Files.writeString(project.resolve("zero-scaffold.json"),
                "{\n  \"schemaVersion\": 1,\n  \"project\": \"sample-game\"\n}\n", StandardCharsets.UTF_8);
        ScaffoldUpgradeService service = new ScaffoldUpgradeService();

        ScaffoldUpgradeService.ScaffoldChangePlan plan = service.plan(request(project));

        assertTrue(plan.requiresMigration());
        ScaffoldUpgradeService.ScaffoldUpgradeException failure = assertThrows(
                ScaffoldUpgradeService.ScaffoldUpgradeException.class, () -> service.apply(request(project)));
        assertTrue(failure.getMessage().startsWith("SCAFFOLD-MIGRATION-REQUIRED|"));
    }

    private Path generate(final String name) throws IOException {
        Path project = temporary.resolve(name);
        ScaffoldCatalog catalog = ScaffoldCatalog.standard();
        new ProjectScaffoldGenerator(catalog.capabilityModel()).generate(request(project));
        return project;
    }

    private ProjectScaffoldRequest request(final Path output) {
        ScaffoldCatalog catalog = ScaffoldCatalog.standard();
        return new ProjectScaffoldRequest("sample-game", "group.zn.sample.game", output,
                "0.1.0-SNAPSHOT", catalog.require("local"), Path.of("../templates"), "", List.of(), false);
    }
}
