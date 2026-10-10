package group.zn.zero.codegen.generator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 提交状态与恢复元数据边界；仅写测试目录。 @author zn */
class OutputTransactionBoundaryTest {
    /** 独占根目录。 */
    @TempDir private Path root;

    @Test
    void committedJournalRecoveryOnlyCleansMetadata() throws Exception {
        Path file = root.resolve("Value.java");
        Files.writeString(file, "after");
        journal(2, "COMMITTED", file);
        new OutputTransaction(root, path -> path.startsWith(root)).recover();
        assertEquals("after", Files.readString(file));
        assertFalse(Files.exists(root.resolve(OutputTransaction.JOURNAL)));
    }

    @Test
    void legacyJournalCannotGuessCommitFact() throws Exception {
        Path file = root.resolve("Value.java");
        Files.writeString(file, "after");
        journal(1, "", file);
        assertThrows(IOException.class, () -> new OutputTransaction(root, path -> path.startsWith(root)).recover());
        assertEquals("after", Files.readString(file));
    }

    @Test
    void failedCommitMarkerRollsBackAndReleasesLocks() throws Exception {
        Path file = root.resolve("Value.java");
        Files.writeString(file, "before");
        var transaction = new OutputTransaction(root, path -> path.startsWith(root));
        var mutations = List.of(new OutputTransaction.Mutation(file.toString(), "before", "after"));
        assertThrows(IOException.class, () -> transaction.apply(mutations, new OutputTransaction.CommitHook() {
            @Override public void beforeWrite(final int index) { }
            @Override public void beforeCommit() throws IOException { throw new IOException("commit failure"); }
        }));
        assertEquals("before", Files.readString(file));
        assertFalse(Files.exists(root.resolve(OutputTransaction.JOURNAL)));
        transaction.apply(mutations);
        assertEquals("after", Files.readString(file));
    }

    @Test
    void cleanupFailureKeepsCommitAndRetryOnlyCleans() throws Exception {
        Path file = root.resolve("Value.java");
        Files.writeString(file, "before");
        var transaction = new OutputTransaction(root, path -> path.startsWith(root));
        assertThrows(OutputTransaction.CommittedCleanupException.class, () -> transaction.apply(
                List.of(new OutputTransaction.Mutation(file.toString(), "before", "after")),
                new OutputTransaction.CommitHook() {
                    @Override public void beforeWrite(final int index) { }
                    @Override public void beforeCleanup() throws IOException { throw new IOException("cleanup failure"); }
                }));
        assertEquals("after", Files.readString(file));
        transaction.cleanupCommitted();
        assertEquals("after", Files.readString(file));
        assertFalse(Files.exists(root.resolve(OutputTransaction.JOURNAL)));
    }

    @Test
    void recoveryPreservesExternalEditAndJournal() throws Exception {
        Path file = root.resolve("Value.java");
        Files.writeString(file, "external");
        journal(2, "PREPARED", file);
        String original = Files.readString(root.resolve(OutputTransaction.JOURNAL));
        assertThrows(IOException.class, () -> new OutputTransaction(root, path -> path.startsWith(root)).recover());
        assertEquals("external", Files.readString(file));
        assertEquals(original, Files.readString(root.resolve(OutputTransaction.JOURNAL)));
    }

    @Test
    void journalCannotEscapeConfiguredRoots() throws Exception {
        Path output = Files.createDirectory(root.resolve("allowed"));
        Path outside = root.resolve("outside.java");
        Files.writeString(outside, "after");
        journal(2, "PREPARED", outside);
        assertThrows(IOException.class, () -> new OutputTransaction(root, path -> path.startsWith(output)).recover());
        assertEquals("after", Files.readString(outside));
    }

    @Test
    void competingOperationCannotWriteUntilLockReleased() throws Exception {
        Path file = root.resolve("Value.java");
        Path meta = Files.createDirectory(root.resolve(".zero-codegen"));
        var transaction = new OutputTransaction(root, path -> path.startsWith(root));
        var mutations = List.of(new OutputTransaction.Mutation(file.toString(), null, "after"));
        try (var channel = java.nio.channels.FileChannel.open(meta.resolve(".zero-codegen.lock"),
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE);
                var lock = channel.lock()) {
            assertThrows(IOException.class, () -> transaction.apply(mutations));
            assertFalse(Files.exists(file));
        }
        transaction.apply(mutations);
        assertEquals("after", Files.readString(file));
    }

    @Test
    void portablePathsRejectDeviceNamesBeforeWriting() throws Exception {
        for (String name : List.of("CON.java", "aux.txt", "COM1.cs", "COM⁴.cs", "LPT⁹.cs", "file.", "trailing ")) {
            Path path;
            try {
                path = root.resolve(name);
            } catch (java.nio.file.InvalidPathException rejectedByPlatform) {
                continue; // Windows Path 自身也会拒绝尾空格；不得要求它延迟到 IO 层。
            }
            assertThrows(IOException.class, () -> OutputFiles.validate(path));
        }
    }

    private void journal(final int schema, final String phase, final Path file) throws Exception {
        Path journal = root.resolve(OutputTransaction.JOURNAL);
        Files.createDirectories(journal.getParent());
        Files.writeString(journal, new Gson().toJson(Map.of("schema", schema, "phase", phase, "mutations",
                List.of(Map.of("path", file.toString(), "before", "before", "after", "after")))));
    }
}
