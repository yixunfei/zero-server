package group.zn.zero.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

/** 世界目录回收与迁移重放水位回归。 @author zn */
class WorldRetentionTest {
    /** 在途迁移阻止回收，终态确认后释放容量并拒绝陈旧重放。 */
    @Test void acknowledgedMigrationsCanBeRetiredSafely() {
        var service = new LocalWorldService(new group.zn.zero.actor.scheduler.LocalActorScheduler(),
                event -> { }, WorldMetrics.NOOP, 1);
        service.createWorld("w", "s");
        service.enterWorldAsync("e", "w").toCompletableFuture().join();
        service.requestMigrationAsync("001", "e", "s").toCompletableFuture().join();
        assertThrows(IllegalStateException.class, () -> service.acknowledgeMigrationsThrough("001"));
        assertThrows(CompletionException.class, () -> service.leaveWorldAsync("e").toCompletableFuture().join());
        assertEquals(1, service.migrationCount());
        service.prepareMigrationAsync("001", false).toCompletableFuture().join();
        assertEquals(1, service.acknowledgeMigrationsThrough("001"));
        assertNull(service.migration("001"));
        assertThrows(CompletionException.class,
                () -> service.requestMigrationAsync("001", "e", "s").toCompletableFuture().join());
        service.migrateAsync("002", "e", "s").toCompletableFuture().join();
        assertThrows(RuntimeException.class, () -> service.removeEmptyWorld("w"));
        assertTrue(service.leaveWorldAsync("e").toCompletableFuture().join());
        assertTrue(service.queryEntity("e").isEmpty());
        assertTrue(service.removeEmptyWorld("w"));
        service.createWorld("w", "s");
    }

    /** 数值序号决定归档边界，字符串字典序不会影响旧重放拒绝。 */
    @Test void explicitSequenceWatermarkIsIndependentFromMigrationId() {
        var service = new LocalWorldService(new group.zn.zero.actor.scheduler.LocalActorScheduler(),
                event -> { }, WorldMetrics.NOOP, 2);
        service.createWorld("w", "s");
        service.enterWorldAsync("e1", "w").toCompletableFuture().join();
        service.requestMigrationAsync(10L, "uuid-z", "e1", "s").toCompletableFuture().join();
        service.prepareMigrationAsync("uuid-z", false).toCompletableFuture().join();
        assertEquals(1, service.acknowledgeMigrationsThrough(10L));
        assertThrows(Exception.class,
                () -> service.requestMigrationAsync(9L, "uuid-a", "e1", "s").toCompletableFuture().join());
    }

    @Test void asynchronousConvenienceMigrationAcceptsExplicitSequence() {
        var service = new LocalWorldService(new group.zn.zero.actor.scheduler.LocalActorScheduler(),
                event -> { }, WorldMetrics.NOOP, 2);
        service.createWorld("w", "s");
        service.enterWorldAsync("e", "w").toCompletableFuture().join();
        Migration migrated = service.migrateAsync(42L, "uuid-migration", "e", "s")
                .toCompletableFuture().join();
        assertEquals(42L, migrated.sequence());
        assertEquals(MigrationState.COMPLETED, migrated.state());
    }
}
