package group.zn.zero.data.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import group.zn.zero.data.envelope.ZeroDataEnvelope;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 本地数据日志持久化与尾部撕裂测试。
 *
 * @author zn
 */
class LocalDiskDataJournalTest {

    /** 临时目录。 */
    @TempDir
    private Path tempDir;

    /** 混合集合批量追加保留各文件内顺序；重启后同样能够恢复撕裂尾帧再批量写入。 */
    @Test
    void batchAppendPreservesPerCollectionOrderAndRecoversTornTail() throws Exception {
        var journal = new LocalDiskDataJournal(tempDir);
        var first = RedisDataJournalEntry.delete("game", "player", "p1", 1, 1000);
        var other = RedisDataJournalEntry.delete("game", "item", "i1", 1, 1000);
        var second = RedisDataJournalEntry.delete("game", "player", "p2", 2, 1001);
        journal.appendAll(List.of(first, other, second));
        assertEquals(List.of("p1", "p2"), journal.readAll("game", "player").stream().map(RedisDataJournalEntry::id).toList());
        assertEquals(List.of("i1"), journal.readAll("game", "item").stream().map(RedisDataJournalEntry::id).toList());
        Files.write(journal.journalPath("game", "player"), new byte[] {(byte) 0x80},
                java.nio.file.StandardOpenOption.APPEND);
        var restarted = new LocalDiskDataJournal(tempDir);
        restarted.appendAll(List.of(second, first, other));
        assertEquals(List.of("p1", "p2", "p2", "p1"), restarted.readAll("game", "player").stream().map(RedisDataJournalEntry::id).toList());
        assertEquals(List.of("i1", "i1"), restarted.readAll("game", "item").stream().map(RedisDataJournalEntry::id).toList());
        restarted.appendAll(List.of());
        assertEquals(4, restarted.readAll("game", "player").size());
    }

    /** 完整记录后追加撕裂记录时，只读取完整前缀。 */
    @Test
    void readAllShouldIgnoreTornTailRecord() throws Exception {
        LocalDiskDataJournal journal = new LocalDiskDataJournal(tempDir);
        ZeroDataEnvelope envelope = new ZeroDataEnvelope(
                "game", "player", "str:cDE", 1L, 1, 1, 1000L, new byte[] {1});
        journal.append(RedisDataJournalEntry.put(envelope, new group.zn.zero.data.envelope.ZeroDataEnvelopeCodec()));
        Path path = journal.journalPath("game", "player");
        Files.write(path, new byte[] {(byte) 0x80}, java.nio.file.StandardOpenOption.APPEND);

        List<RedisDataJournalEntry> entries = journal.readAll("game", "player");

        assertEquals(1, entries.size());
    }

    /** 重启后丢弃撕裂尾帧再追加，后续完整记录仍可读取。 */
    @Test
    void appendAfterTornTailRecoversBothPrefixAndNewRecord() throws Exception {
        for (byte[] tail : List.of(new byte[] {(byte) 0x80}, new byte[] {100, 1, 2})) {
            Path root = Files.createTempDirectory(tempDir, "tail");
            var journal = new LocalDiskDataJournal(root);
            var entry = RedisDataJournalEntry.delete("game", "player", "id", 1L, 1000L);
            journal.append(entry);
            Files.write(journal.journalPath("game", "player"), tail, java.nio.file.StandardOpenOption.APPEND);
            assertEquals(1, journal.readAll("game", "player").size());
            var restarted = new LocalDiskDataJournal(root);
            restarted.append(entry);
            assertEquals(2, restarted.readAll("game", "player").size());
        }
    }

    /** 完整帧损坏应报错，不应伪装成可截断尾部。 */
    @Test
    void completeCorruptFrameFailsReadAndAppend() throws Exception {
        var journal = new LocalDiskDataJournal(tempDir);
        var entry = RedisDataJournalEntry.delete("game", "player", "id", 1L, 1000L);
        journal.append(entry);
        Files.write(journal.journalPath("game", "player"), new byte[] {1, (byte) 0xFF},
                java.nio.file.StandardOpenOption.APPEND);
        assertThrows(group.zn.zero.core.error.ZeroException.class, () -> journal.readAll("game", "player"));
        assertThrows(group.zn.zero.core.error.ZeroException.class, () -> journal.append(entry));
    }

    /** 路径片段拒绝 Windows ADS、非法文件名字符和控制字符。 */
    @Test
    void journalPathShouldRejectUnsafeWindowsSegments() {
        var journal = new LocalDiskDataJournal(tempDir);

        assertThrows(IllegalArgumentException.class, () -> journal.journalPath("game:logs", "player"));
        assertThrows(IllegalArgumentException.class, () -> journal.journalPath("game", "player\u0000"));
        assertThrows(IllegalArgumentException.class, () -> journal.journalPath("game", "player?"));
        assertThrows(IllegalArgumentException.class, () -> journal.journalPath(".", "player"));
        assertThrows(IllegalArgumentException.class, () -> journal.journalPath("game", "player."));
    }
}
