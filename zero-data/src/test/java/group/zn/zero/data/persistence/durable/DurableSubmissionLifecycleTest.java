package group.zn.zero.data.persistence.durable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.data.envelope.InMemoryEnvelopeStore;
import group.zn.zero.data.envelope.ZeroDataEnvelope;
import group.zn.zero.data.persistence.DataThreadBinding;
import group.zn.zero.data.persistence.PersistenceBindingExecutor;
import group.zn.zero.data.persistence.PersistenceRetryPolicy;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 耐久准入后的取消、捕获异常和关闭竞争不能丢失意图或悬挂关闭。 @author zn */
class DurableSubmissionLifecycleTest {
    /** 每测试独占日志目录。 */
    @TempDir Path directory;

    /** 取消调用者句柄不取消已接受的捕获，关闭必须等待其刷盘完成。 */
    @Test void closeWaitsForAcceptedCaptureDespiteCallerCancellation() throws Exception {
        var gate = new CompletableFuture<Void>();
        var binding = new DeferredBinding(gate);
        try (var io = Executors.newSingleThreadExecutor()) {
            var service = service(binding, io);
            var receipt = service.submit("op", "p", 0, DataThreadBinding.unbound(), this::envelope).toCompletableFuture();
            assertTrue(receipt.cancel(false));
            var close = service.closeAsync().toCompletableFuture();
            assertFalse(close.isDone());
            assertThrows(Exception.class, () -> service.submit("late", "p", 0,
                    DataThreadBinding.unbound(), this::envelope).toCompletableFuture().get(2, TimeUnit.SECONDS));
            gate.complete(null);
            close.get(2, TimeUnit.SECONDS);
        }
        try (var journal = new LocalDurableJournal(directory, 1_000_000, 10)) {
            assertEquals(1, journal.records().size());
            assertEquals(DurableRecord.State.PENDING, journal.records().getFirst().state());
        }
    }

    /** 捕获器同步抛出 Error 时结果失败并释放准入，不能永久卡住 close。 */
    @Test void captureErrorCompletesReceiptAndReleasesAdmission() throws Exception {
        PersistenceBindingExecutor binding = new PersistenceBindingExecutor() {
            /** 模拟不符合契约的外部快照执行器。 */
            @Override public <T> CompletionStage<T> capture(DataThreadBinding key, Supplier<T> supplier) {
                throw new AssertionError("capture failure");
            }
        };
        try (var io = Executors.newSingleThreadExecutor()) {
            var service = service(binding, io);
            assertThrows(Exception.class, () -> service.submit("op", "p", 0, DataThreadBinding.unbound(),
                    this::envelope).toCompletableFuture().get(2, TimeUnit.SECONDS));
            service.closeAsync().toCompletableFuture().get(2, TimeUnit.SECONDS);
        }
        try (var journal = new LocalDurableJournal(directory, 1_000_000, 10)) {
            assertTrue(journal.records().isEmpty());
        }
    }

    /** 隔离结果跨重启保留，启动不会重置重试预算。 */
    @Test void quarantineSurvivesRestart() throws Exception {
        var store = new InMemoryEnvelopeStore();
        store.save(new ZeroDataEnvelope("game", "p", "p", 2, 1, 1, 1, new byte[] {2}));
        try (var io = Executors.newSingleThreadExecutor(); var service = new DurablePersistenceService(
                new LocalDurableJournal(directory, 1_000_000, 10), Map.of("p", store),
                new DeferredBinding(CompletableFuture.completedFuture(null)), io, Clock.systemUTC(),
                new PersistenceRetryPolicy(2, Duration.ZERO, Duration.ZERO))) {
            service.submit("op", "p", 0, DataThreadBinding.unbound(), this::envelope)
                    .toCompletableFuture().get(2, TimeUnit.SECONDS);
            service.flushNow(1).toCompletableFuture().get(2, TimeUnit.SECONDS);
        }
        try (var io = Executors.newSingleThreadExecutor(); var service = service(
                new DeferredBinding(CompletableFuture.completedFuture(null)), io)) {
            assertEquals(DurableRecord.State.QUARANTINED, service.records().getFirst().state());
            assertEquals(1, service.records().getFirst().attempts());
            assertEquals(0, service.flushNow(1).toCompletableFuture().get(2, TimeUnit.SECONDS));
        }
    }

    private DurablePersistenceService service(final PersistenceBindingExecutor binding, final java.util.concurrent.Executor io) {
        return new DurablePersistenceService(new LocalDurableJournal(directory, 1_000_000, 10),
                Map.of("p", new InMemoryEnvelopeStore()), binding, io, Clock.systemUTC(), PersistenceRetryPolicy.defaults());
    }

    private ZeroDataEnvelope envelope() {
        return new ZeroDataEnvelope("game", "p", "p", 1, 1, 1, 1, new byte[] {1});
    }

    /** 由测试显式释放捕获屏障。 */
    private record DeferredBinding(CompletableFuture<Void> gate) implements PersistenceBindingExecutor {
        /** 屏障释放前不能访问快照。 */
        @Override public <T> CompletionStage<T> capture(DataThreadBinding key, Supplier<T> supplier) {
            return gate.thenApply(ignored -> supplier.get());
        }
    }
}
