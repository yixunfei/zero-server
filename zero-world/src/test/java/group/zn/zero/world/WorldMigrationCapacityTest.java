package group.zn.zero.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import group.zn.zero.actor.scheduler.LocalActorScheduler;
import group.zn.zero.core.error.ZeroException;
import java.util.List;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

/** 验证迁移容量满时仍保留既有收据和推进能力。 */
class WorldMigrationCapacityTest {
    @Test void fullCapacityRetainsReceiptsAndPermitsEveryExistingStage() {
        var service = new LocalWorldService(new LocalActorScheduler(), event -> { }, WorldMetrics.NOOP, 1);
        service.createWorld(new World("w", 7), List.of(new Shard("a", "w", 7), new Shard("b", "w", 7)));
        var first = service.enterWorldAsync("first", "w").toCompletableFuture().join();
        var other = service.enterWorldAsync("other", "w").toCompletableFuture().join();
        assertEquals(7, first.routeEpoch());
        assertThrows(CompletionException.class,
                () -> service.requestMigrationAsync(1L, "bad", "first", "b", 6).toCompletableFuture().join());
        service.requestMigrationAsync(2L, "m", "first", "b", 7).toCompletableFuture().join();
        var rejected = assertThrows(CompletionException.class,
                () -> service.requestMigrationAsync(3L, "n", "other", "b", 7).toCompletableFuture().join());
        assertEquals(WorldErrorCode.MIGRATION_CAPACITY_EXCEEDED, ((ZeroException) rejected.getCause()).errorCode());
        assertEquals(EntitySnapshot.of(other), service.snapshot("other"));
        assertThrows(CompletionException.class,
                () -> service.requestMigrationAsync(2L, "m", "other", "b", 7).toCompletableFuture().join());
        service.prepareMigrationAsync("m").toCompletableFuture().join();
        service.commitMigrationAsync("m").toCompletableFuture().join();
        var completed = service.releaseSourceAsync("m").toCompletableFuture().join();
        assertEquals(MigrationState.COMPLETED, completed.state());
        assertSame(completed, service.requestMigrationAsync(2L, "m", "first", "b", 7).toCompletableFuture().join());
        assertEquals(1, service.migrationCount());
    }

    @Test void failedReceiptRemainsReplayableAtCapacity() {
        var service = new LocalWorldService(new LocalActorScheduler(), event -> { }, WorldMetrics.NOOP, 1);
        service.createWorld("w", "a");
        service.enterWorldAsync("entity", "w").toCompletableFuture().join();
        service.requestMigrationAsync(2L, "m", "entity", "a").toCompletableFuture().join();
        var failed = service.prepareMigrationAsync("m", false).toCompletableFuture().join();
        assertEquals(MigrationState.FAILED, failed.state());
        assertSame(failed, service.requestMigrationAsync(2L, "m", "entity", "a").toCompletableFuture().join());
        assertEquals(EntityStatus.ACTIVE, service.entityStatus("entity"));
    }
}
