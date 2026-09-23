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
}
