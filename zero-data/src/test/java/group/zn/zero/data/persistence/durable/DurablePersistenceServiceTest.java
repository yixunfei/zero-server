package group.zn.zero.data.persistence.durable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.core.error.ZeroException;
import group.zn.zero.data.envelope.InMemoryEnvelopeStore;
import group.zn.zero.data.envelope.ZeroDataEnvelope;
import group.zn.zero.data.envelope.ZeroDataEnvelopeStore;
import group.zn.zero.data.persistence.DataThreadBinding;
import group.zn.zero.data.persistence.DirectPersistenceBindingExecutor;
import group.zn.zero.data.persistence.PersistenceRetryPolicy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 耐久确认、恢复、未知写结果、冲突隔离和日志预算回归。 @author zn */
class DurablePersistenceServiceTest {
    /** 每个测试独占目录。 */
    @TempDir Path directory;

    /** 确認成功但未落库的子 JVM 立即退出，父 JVM 从日志恢复 CAS。 */
    @Test void acknowledgedIntentSurvivesAbruptJvmExit() throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process child = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                DurableCrashWriter.class.getName(), directory.toString()).redirectErrorStream(true)
                .redirectOutput(directory.resolve("child.log").toFile()).start();
        try {
            assertTrue(child.waitFor(10, TimeUnit.SECONDS)); assertEquals(0, child.exitValue());
        } finally { if (child.isAlive()) child.destroyForcibly(); }
        var store = new InMemoryEnvelopeStore();
        try (var executor = Executors.newSingleThreadExecutor(); var service = service(store, executor)) {
            assertTrue(store.findById("p").isEmpty());
            assertEquals(1, service.flushNow(10).toCompletableFuture().get(2, TimeUnit.SECONDS));
            assertEquals(7, store.findById("p").orElseThrow().payload()[0]);
            assertEquals(DurableRecord.State.APPLIED, service.records().getFirst().state());
        }
    }

    /** 后端写成功但响应丢失，下一次仅核验快照，不重复 CAS 或递增版本。 */
    @Test void lostResponseIsReconciledAndReceiptIsIdempotent() throws Exception {
        var store = new UnknownResultStore();
        try (var executor = Executors.newSingleThreadExecutor(); var service = service(store, executor)) {
            var first = submit(service, "op", envelope(1, 1));
            assertEquals(DurableRecord.State.PENDING, first.state());
            assertEquals(0, service.flushNow(10).toCompletableFuture().get(2, TimeUnit.SECONDS));
            assertEquals(1, service.flushNow(10).toCompletableFuture().get(2, TimeUnit.SECONDS));
            assertEquals(1, store.writes);
            assertEquals(DurableRecord.State.APPLIED, submit(service, "op", envelope(1, 1)).state());
            assertThrows(Exception.class, () -> submit(service, "op", envelope(1, 2)));
            service.compact().toCompletableFuture().get(2, TimeUnit.SECONDS);
        }
        try (var executor = Executors.newSingleThreadExecutor(); var reopened = service(store, executor)) {
            assertEquals(DurableRecord.State.APPLIED, reopened.records().getFirst().state());
            assertEquals(0, reopened.flushNow(10).toCompletableFuture().get(2, TimeUnit.SECONDS));
        }
    }

    /** 拒绝过期 CAS；隔离后不自动重放，重试仍不能覆盖不同内容。 */
    @Test void conflictIsQuarantinedWithoutOverwritingWinner() throws Exception {
        var store = new InMemoryEnvelopeStore(); store.save(envelope(1, 9));
        try (var executor = Executors.newSingleThreadExecutor(); var service = service(store, executor)) {
            submit(service, "op", envelope(1, 1));
            assertEquals(0, service.flushNow(10).toCompletableFuture().get(2, TimeUnit.SECONDS));
            assertEquals(DurableRecord.State.QUARANTINED, service.records().getFirst().state());
            assertThrows(Exception.class, () -> submit(service, "other", envelope(1, 2)));
            service.retry("op").toCompletableFuture().get(2, TimeUnit.SECONDS);
            service.flushNow(10).toCompletableFuture().get(2, TimeUnit.SECONDS);
            assertEquals(9, store.findById("p").orElseThrow().payload()[0]);
        }
    }

    /** 半帧尾部可恢复；同目录独占，完整帧内容损坏不得默默丢弃。 */
    @Test void journalRecoversTornTailButRejectsCorruptionAndDoubleOpen() throws Exception {
        try (var journal = new LocalDurableJournal(directory, 1_000_000, 10)) {
            journal.append(new DurableRecord("op", "players", 0, envelope(1, 1), DurableRecord.State.PENDING, 0, 0, ""));
            assertThrows(ZeroException.class, () -> new LocalDurableJournal(directory, 1_000_000, 10));
        }
        Path log = directory.resolve("intents.zwal"); long completeSize = Files.size(log);
        Files.write(log, new byte[] {0, 0, 0}, StandardOpenOption.APPEND);
        try (var journal = new LocalDurableJournal(directory, 1_000_000, 10)) {
            assertEquals(1, journal.records().size()); assertEquals(completeSize, Files.size(log));
        }
        byte[] original = Files.readAllBytes(log);
        byte[] bytes = original.clone();
        bytes[11] ^= 1; Files.write(log, bytes);
        assertThrows(ZeroException.class, () -> new LocalDurableJournal(directory, 1_000_000, 10));
        bytes = original.clone(); bytes[bytes.length - 1] ^= 1; Files.write(log, bytes);
        assertThrows(ZeroException.class, () -> new LocalDurableJournal(directory, 1_000_000, 10));
    }

    /** 操作预算满时明确拒绝，既有待执行意图继续保留。 */
    @Test void exhaustedJournalDoesNotEvictAcceptedIntent() {
        try (var journal = new LocalDurableJournal(directory, 1_000_000, 1)) {
            journal.append(new DurableRecord("first", "players", 0, envelope(1, 1), DurableRecord.State.PENDING, 0, 0, ""));
            assertThrows(ZeroException.class, () -> journal.append(new DurableRecord("second", "players", 0,
                    envelope(1, 2), DurableRecord.State.PENDING, 0, 0, "")));
            assertEquals("first", journal.records().getFirst().operationId());
        }
    }

    /** 模拟日志路径不可写：不能返回耐久确认，实例拒绝继续追加直到重新恢复。 */
    @Test void diskFailureDoesNotAcknowledgeIntent() throws Exception {
        try (var journal = new LocalDurableJournal(directory, 1_000_000, 10)) {
            Path log = directory.resolve("intents.zwal");
            Files.delete(log);
            Files.createDirectory(log);
            var intent = new DurableRecord("op", "players", 0, envelope(1, 1), DurableRecord.State.PENDING, 0, 0, "");
            assertThrows(ZeroException.class, () -> journal.append(intent));
            assertThrows(ZeroException.class, () -> journal.append(intent));
        }
    }

    private DurablePersistenceService service(final ZeroDataEnvelopeStore store, final java.util.concurrent.Executor executor) {
        return new DurablePersistenceService(new LocalDurableJournal(directory, 1_000_000, 100), Map.of("players", store),
                new DirectPersistenceBindingExecutor(), executor, Clock.systemUTC(),
                new PersistenceRetryPolicy(2, Duration.ZERO, Duration.ZERO));
    }

    private DurableRecord submit(final DurablePersistenceService service, final String operationId,
            final ZeroDataEnvelope envelope) throws Exception {
        return service.submit(operationId, "players", envelope.version() - 1, DataThreadBinding.unbound(), () -> envelope)
                .toCompletableFuture().get(2, TimeUnit.SECONDS);
    }

    private ZeroDataEnvelope envelope(final long version, final int value) {
        return new ZeroDataEnvelope("game", "players", "p", version, 1, 1, 1, new byte[] {(byte) value});
    }

    /** 模拟后端已经提交但调用者没有收到成功响应。 */
    private static final class UnknownResultStore implements ZeroDataEnvelopeStore {
        /** 权威数据。 */
        private final InMemoryEnvelopeStore delegate = new InMemoryEnvelopeStore();
        /** 实际 CAS 次数。 */
        private int writes;
        /** @return 权威快照。 */
        public Optional<ZeroDataEnvelope> findById(String id) { return delegate.findById(id); }
        /** @return 全部快照。 */
        public List<ZeroDataEnvelope> findAll() { return delegate.findAll(); }
        /** 保存快照。 */
        public void save(ZeroDataEnvelope value) { delegate.save(value); }
        /** 提交后故意断开响应。 */
        public boolean saveIfVersion(ZeroDataEnvelope value, long expected) {
            writes++; delegate.saveIfVersion(value, expected); throw new IllegalStateException("response lost");
        }
        /** 删除测试数据。 */
        public void deleteById(String id) { delegate.deleteById(id); }
        /** @return 数量。 */
        public long count() { return delegate.count(); }
    }
}
