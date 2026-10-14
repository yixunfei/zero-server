package group.zn.zero.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

/** 世界目录回收与迁移重放水位回归。 @author zn */
class WorldRetentionTest {
    /** 容量一的目录经历千次归档后没有保留历史 ID 映射，旧序号仍拒绝。 */
    @Test void churnReleasesAllMigrationMaps() throws Exception {
        var service = new LocalWorldService(new group.zn.zero.actor.scheduler.LocalActorScheduler(),
                event -> { }, WorldMetrics.NOOP, 1);
        service.createWorld("w", "s");
        service.enterWorldAsync("e", "w").toCompletableFuture().join();
        for (int i = 1; i <= 1000; i++) {
            service.migrateAsync(i, "migration-" + i, "e", "s").toCompletableFuture().join();
            assertEquals(1, service.acknowledgeMigrationsThrough(i));
            assertEquals(0, service.migrationCount());
        }
        var field = LocalWorldService.class.getDeclaredField("migrations");
        field.setAccessible(true);
        Object records = field.get(service);
        for (var directory : records.getClass().getDeclaredFields()) {
            if (java.util.Map.class.isAssignableFrom(directory.getType())) {
                directory.setAccessible(true);
                assertTrue(((java.util.Map<?, ?>) directory.get(records)).isEmpty(), directory.getName());
            }
        }
        assertEquals(0, service.acknowledgeMigrationsThrough(1000));
        assertThrows(IllegalArgumentException.class, () -> service.acknowledgeMigrationsThrough(999));
        assertThrows(CompletionException.class,
                () -> service.migrateAsync(999, "old", "e", "s").toCompletableFuture().join());
    }
    /** 在途迁移阻止回收，终态确认后释放容量并拒绝陈旧重放。 */
    @Test void acknowledgedMigrationsCanBeRetiredSafely() {
        var service = new LocalWorldService(new group.zn.zero.actor.scheduler.LocalActorScheduler(),
                event -> { }, WorldMetrics.NOOP, 1);
        service.createWorld("w", "s");
        service.enterWorldAsync("e", "w").toCompletableFuture().join();
        service.requestMigrationAsync(1L, "001", "e", "s").toCompletableFuture().join();
        assertThrows(IllegalStateException.class, () -> service.acknowledgeMigrationsThrough( 1L));
        assertThrows(CompletionException.class, () -> service.leaveWorldAsync("e").toCompletableFuture().join());
        assertEquals(1, service.migrationCount());
        service.prepareMigrationAsync("001", false).toCompletableFuture().join();
        assertEquals(1, service.acknowledgeMigrationsThrough( 1L));
        assertNull(service.migration("001"));
        assertThrows(CompletionException.class,
                () -> service.requestMigrationAsync(1L, "001", "e", "s").toCompletableFuture().join());
        service.migrateAsync(2L, "002", "e", "s").toCompletableFuture().join();
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
