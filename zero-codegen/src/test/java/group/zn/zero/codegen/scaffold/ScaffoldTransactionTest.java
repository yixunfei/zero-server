package group.zn.zero.codegen.scaffold;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 脚手架 staging 中断与 rollback focused tests。 */
class ScaffoldTransactionTest {

    @TempDir
    private Path temporary;

    @Test
    void replacementFailureLeavesRecoveryStateAndRollbackRestoresSnapshot() throws Exception {
        Path project = generate("failure");
        Path application = project.resolve("src/main/java/group/zn/sample/game/SampleGameApplication.java");
        String before = Files.readString(application, StandardCharsets.UTF_8);
        AtomicInteger calls = new AtomicInteger();
        ScaffoldUpgradeService service = new ScaffoldUpgradeService((source, destination) -> {
            if (calls.incrementAndGet() == 2) {
                throw new IOException("injected replacement failure");
            }
            Files.createDirectories(destination.getParent());
            Files.move(source, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        });
        var request = request(project, "0.1.0-updated");

        try {
            service.apply(request);
        } catch (IOException expected) {
            // 故障注入验证事务必须保留恢复材料。
        }

        Path transactions = project.resolve(".zero/scaffold/transactions");
        Path transaction = Files.list(transactions).findFirst().orElseThrow();
        assertEquals("RECOVERY_REQUIRED", Files.readString(transaction.resolve("state"), StandardCharsets.UTF_8).trim());
        String id = transaction.getFileName().toString();
        Files.writeString(project.resolve(".zero/scaffold/LATEST"), id + "\n", StandardCharsets.UTF_8);
        service.rollback(project);
        assertEquals(before, Files.readString(application, StandardCharsets.UTF_8));
    }

    @Test
    void concurrentApplyIsRejectedAndLockIsReleased() throws Exception {
        Path project = generate("lock");
        var request = request(project, "0.1.0-updated");
        Path lock = project.resolve(".zero/scaffold/upgrade.lock");
        Files.createDirectories(lock.getParent());
        java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(lock,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE);
        java.nio.channels.FileLock held = channel.lock();
        try {
            ScaffoldUpgradeService.ScaffoldUpgradeException failure = assertThrows(
                    ScaffoldUpgradeService.ScaffoldUpgradeException.class,
                    () -> new ScaffoldUpgradeService().apply(request));
            assertTrue(failure.getMessage().startsWith("SCAFFOLD-UPGRADE-LOCKED|"));
        } finally {
            held.release();
            channel.close();
        }
        assertEquals("COMMITTED", new ScaffoldUpgradeService().apply(request).state());
    }

    @Test
    void rollbackRemovesNewFileCreatedDuringPartialCommit() throws Exception {
        Path project = generate("new-file");
        Path application = project.resolve("src/main/java/group/zn/sample/game/SampleGameApplication.java");
        AtomicInteger calls = new AtomicInteger();
        ScaffoldUpgradeService service = new ScaffoldUpgradeService((source, destination) -> {
            if (calls.incrementAndGet() == 2) {
                throw new IOException("injected replacement failure");
            }
            Files.createDirectories(destination.getParent());
            Files.move(source, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        });
        var request = request(project, "0.1.0-updated");
        try {
            service.apply(request);
        } catch (IOException expected) {
            // expected recovery state
        }
        Path transactions = project.resolve(".zero/scaffold/transactions");
        Path transaction = Files.list(transactions).findFirst().orElseThrow();
        Path after = transaction.resolve("after");
        Path newFile = after.resolve("src/main/java/group/zn/sample/game/NewGenerated.java");
        Files.createDirectories(newFile.getParent());
        Files.writeString(newFile, "new", StandardCharsets.UTF_8);
        Files.writeString(project.resolve(".zero/scaffold/LATEST"), transaction.getFileName() + "\n", StandardCharsets.UTF_8);
        service.rollback(project);
        assertTrue(!Files.exists(project.resolve("src/main/java/group/zn/sample/game/NewGenerated.java")));
    }

    @Test
    void stagedApplyHasNoTemporaryFilesOutsideTransaction() throws Exception {
        Path project = generate("staging");
        var service = new ScaffoldUpgradeService();
        var transaction = service.apply(request(project, "0.1.0-updated"));

        assertEquals("COMMITTED", transaction.state());
        assertTrue(Files.isDirectory(transaction.directory().resolve("before")));
        assertTrue(Files.isDirectory(transaction.directory().resolve("after")));
        try (var stream = Files.list(project)) {
            assertTrue(stream.noneMatch(path -> path.getFileName().toString().startsWith(".zero-manifest-")));
        }
    }

    private Path generate(final String name) throws IOException {
        Path project = temporary.resolve(name);
        new ProjectScaffoldGenerator(ScaffoldCatalog.standard().capabilityModel()).generate(request(project, "0.1.0-SNAPSHOT"));
        return project;
    }

    private ProjectScaffoldRequest request(final Path output, final String version) {
        ScaffoldCatalog catalog = ScaffoldCatalog.standard();
        return new ProjectScaffoldRequest("sample-game", "group.zn.sample.game", output, version,
                catalog.require("local"), Path.of("../templates"), "", List.of(), false);
    }
}
