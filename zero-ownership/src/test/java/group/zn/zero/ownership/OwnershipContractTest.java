package group.zn.zero.ownership;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** 验证 ownership、fencing、迁移恢复和容量治理的通用契约。 */
class OwnershipContractTest {
    /** 确认归档后释放容量，旧请求和失效租约均不得越过水位。 */
    @Test void acknowledgedStateReceiptsReleaseCapacityWithoutReplayingOldIds() {
        var ownership = new InMemoryOwnershipStore();
        var resource = new OwnershipResource("state", "ack", 1);
        var lease = ownership.acquire(resource, new ProcessIncarnation("p", "i"), Duration.ofMinutes(1));
        var state = new InMemoryFencedStateStore<String>(ownership, Clock.systemUTC(), 1);
        var first = new StateCommitRequest<>(resource, lease, StateVersion.initial(), "001", "one");
        state.commit(first);
        assertEquals(1, state.acknowledgeRequestsThrough(lease, "001"));
        assertThrows(OwnershipException.class, () -> state.commit(first));
        state.commit(new StateCommitRequest<>(resource, lease, new StateVersion(1), "002", "two"));
        assertEquals("two", state.read(resource).orElseThrow().value());
        assertThrows(OwnershipException.class, () -> state.acknowledgeRequestsThrough(lease, "000"));
        ownership.release(lease);
        assertThrows(OwnershipException.class, () -> state.acknowledgeRequestsThrough(lease, "002"));
    }
    @Test
    void ownershipEpochAndGenerationFenceStaleIncarnation() {
        MutableClock clock = new MutableClock();
        InMemoryOwnershipStore store = new InMemoryOwnershipStore(clock);
        OwnershipResource resource = new OwnershipResource("entity", "42", 1);
        ProcessIncarnation first = new ProcessIncarnation("node", "a");
        ProcessIncarnation second = new ProcessIncarnation("node", "b");
        OwnershipLease lease = store.acquire(resource, first, Duration.ofSeconds(5));

        assertThrows(OwnershipException.class, () -> store.acquire(resource, second, Duration.ofSeconds(5)));
        clock.advance(Duration.ofSeconds(6));
        OwnershipLease claimed = store.claim(resource, second, Duration.ofSeconds(5));
        assertEquals(lease.epoch() + 1, claimed.epoch());
        assertFalse(store.release(lease));
        assertThrows(OwnershipException.class, () -> store.renew(lease, Duration.ofSeconds(5)));
        assertThrows(OwnershipException.class, () -> store.assertCurrent(lease));
        OwnershipResource recreated = new OwnershipResource("entity", "42", 2);
        OwnershipLease recreatedLease = store.acquire(recreated, first, Duration.ofSeconds(5));
        assertEquals(1, recreatedLease.epoch());
    }

    /** 极端 epoch 溢出必须失败，不能回绕为可用的负 token。 */
    @Test void epochOverflowDoesNotChangeAuthority() throws Exception {
        var store = new InMemoryOwnershipStore();
        var resource = new OwnershipResource("overflow", "entity", 1);
        var owner = new ProcessIncarnation("process", "incarnation");
        var field = InMemoryOwnershipStore.class.getDeclaredField("epochs");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        var epochs = (java.util.Map<OwnershipResource, Long>) field.get(store);
        epochs.put(resource, Long.MAX_VALUE);
        assertThrows(ArithmeticException.class, () -> store.acquire(resource, owner, Duration.ofSeconds(1)));
        assertEquals(Long.MAX_VALUE, epochs.get(resource));
        assertTrue(store.current(resource, Instant.now()).isEmpty());
    }

    @Test
    void fencedCommitUsesCasAndRequestIdempotency() {
        InMemoryOwnershipStore ownership = new InMemoryOwnershipStore();
        OwnershipResource resource = new OwnershipResource("state", "1", 0);
        OwnershipLease lease = ownership.acquire(resource, new ProcessIncarnation("p", "i"), Duration.ofMinutes(1));
        InMemoryFencedStateStore<String> state = new InMemoryFencedStateStore<>(ownership);
        StateCommitRequest<String> request = new StateCommitRequest<>(resource, lease, StateVersion.initial(), "r1", "v1");

        CommitResult<String> committed = state.commit(request);
        CommitResult<String> replayed = state.commit(request);
        assertTrue(committed.committed());
        assertTrue(replayed.replayed());
        assertEquals(committed.newVersion(), replayed.newVersion());
        assertThrows(OwnershipException.class, () -> state.commit(
                new StateCommitRequest<>(resource, lease, StateVersion.initial(), "r1", "different")));
        assertThrows(OwnershipException.class, () -> state.commit(
                new StateCommitRequest<>(resource, lease, StateVersion.initial(), "r2", "v2")));
    }

    /** 收据满时保留既有重放记录，并拒绝新键；过期 lease 不能借收据得到成功结果。 */
    @Test void fencedReceiptsAreCapacityBoundAndLeaseFenced() {
        MutableClock clock = new MutableClock();
        InMemoryOwnershipStore ownership = new InMemoryOwnershipStore(clock);
        OwnershipResource resource = new OwnershipResource("state", "capacity", 0);
        ProcessIncarnation first = new ProcessIncarnation("p", "one");
        OwnershipLease lease = ownership.acquire(resource, first, Duration.ofSeconds(1));
        InMemoryFencedStateStore<String> state = new InMemoryFencedStateStore<>(ownership, clock, 1);
        StateCommitRequest<String> committed = new StateCommitRequest<>(resource, lease, StateVersion.initial(), "first", "v1");
        state.commit(committed);
        assertTrue(state.commit(committed).replayed());
        assertThrows(OwnershipException.class, () -> state.commit(
                new StateCommitRequest<>(resource, lease, new StateVersion(1), "second", "v2")));
        clock.advance(Duration.ofSeconds(2));
        assertThrows(OwnershipException.class, () -> state.commit(committed));
    }

    /** 补齐历史后更新 checkpoint；陈旧 CAS 和提交后变更必须拒绝。 */
    @Test
    void checkpointMetadataCanBeCompletedBeforeCommit() {
        var store = new InMemoryMigrationStore();
        var id = new MigrationId("checkpoint-cas");
        var incomplete = new MigrationCheckpointMetadata("1", "1", false);
        var complete = new MigrationCheckpointMetadata("1", "1", true);
        store.create(new MigrationRecord(id, new OwnershipResource("world", "1", 0),
                new ProcessIncarnation("p1", "i1"), new ProcessIncarnation("p2", "i1"),
                MigrationState.FROZEN, StateVersion.initial(), Instant.EPOCH, incomplete));
        var updated = store.updateCheckpoint(id, StateVersion.initial(), incomplete, complete, Instant.now());
        assertTrue(updated.checkpointMetadata().historyComplete());
        assertThrows(OwnershipException.class, () -> store.updateCheckpoint(id, StateVersion.initial(),
                incomplete, complete, Instant.now()));
        store.transition(id, MigrationState.CHECKPOINTED, StateVersion.initial(), Instant.now());
        store.transition(id, MigrationState.COMMITTED, StateVersion.initial(), Instant.now());
        assertThrows(OwnershipException.class, () -> store.updateCheckpoint(id, StateVersion.initial(),
                complete, incomplete, Instant.now()));
        assertTrue(store.find(id).orElseThrow().checkpointMetadata().historyComplete());
    }

    @Test
    void migrationCanResumeAndRejectIllegalJump() {
        InMemoryMigrationStore store = new InMemoryMigrationStore();
        MigrationId id = new MigrationId("m1");
        MigrationRecord record = new MigrationRecord(id, new OwnershipResource("x", "1", 0),
                new ProcessIncarnation("p1", "i1"), new ProcessIncarnation("p2", "i1"),
                MigrationState.REQUESTED, StateVersion.initial(), Instant.EPOCH);
        assertEquals(record, store.create(record));
        assertEquals(record, store.create(record));
        assertThrows(OwnershipException.class, () -> store.transition(id, MigrationState.COMMITTED,
                StateVersion.initial(), Instant.now()));
        store.transition(id, MigrationState.FROZEN, StateVersion.initial(), Instant.now());
        store.transition(id, MigrationState.CHECKPOINTED, new StateVersion(3), Instant.now());
        MigrationRecord resumed = store.find(id).orElseThrow();
        assertEquals(MigrationState.CHECKPOINTED, resumed.state());
        assertEquals(3, resumed.checkpointVersion().value());
        assertThrows(OwnershipException.class, () -> store.transition(id, MigrationState.CHECKPOINTED,
                new StateVersion(2), Instant.now()));
        assertThrows(OwnershipException.class, () -> store.transition(id, MigrationState.COMMITTED,
                new StateVersion(2), Instant.now()));
        assertEquals(resumed, store.find(id).orElseThrow());
    }

    @Test
    void failoverClaimsAfterCheckpointAndActivatesWithFencing() {
        MutableClock clock = new MutableClock();
        InMemoryOwnershipStore ownership = new InMemoryOwnershipStore(clock);
        InMemoryMigrationStore migrations = new InMemoryMigrationStore();
        FailoverCoordinator coordinator = new FailoverCoordinator(ownership, migrations, clock);
        OwnershipResource resource = new OwnershipResource("world", "w1", 1);
        ProcessIncarnation source = new ProcessIncarnation("p1", "i1");
        ProcessIncarnation target = new ProcessIncarnation("p2", "i1");
        OwnershipLease sourceLease = ownership.acquire(resource, source, Duration.ofSeconds(5));
        MigrationId id = new MigrationId("failover-1");
        coordinator.prepare(new MigrationRecord(id, resource, source, target, MigrationState.REQUESTED,
                StateVersion.initial(), clock.instant()));
        migrations.transition(id, MigrationState.FROZEN, StateVersion.initial(), clock.instant());
        migrations.transition(id, MigrationState.CHECKPOINTED, new StateVersion(4), clock.instant());
        coordinator.commitCheckpoint(id, new StateVersion(4));
        clock.advance(Duration.ofSeconds(6));
        OwnershipLease targetLease = coordinator.claim(id, Duration.ofSeconds(5));
        assertEquals(targetLease, coordinator.claim(id, Duration.ofSeconds(5)));
        coordinator.activate(id, targetLease);
        assertEquals(MigrationState.ACTIVATED, migrations.find(id).orElseThrow().state());
        assertThrows(OwnershipException.class, () -> coordinator.activate(id, sourceLease));
        coordinator.complete(id);
    }

    @Test
    void recoveryResumesCommittedMigrationAndRouteIsEpochFenced() {
        MutableClock clock = new MutableClock();
        InMemoryOwnershipStore ownership = new InMemoryOwnershipStore(clock);
        InMemoryMigrationStore migrations = new InMemoryMigrationStore();
        InMemoryOwnershipRouter routes = new InMemoryOwnershipRouter(clock);
        OwnershipResource resource = new OwnershipResource("entity", "recover", 1);
        ProcessIncarnation source = new ProcessIncarnation("source", "one");
        ProcessIncarnation target = new ProcessIncarnation("target", "one");
        OwnershipLease sourceLease = ownership.acquire(resource, source, Duration.ofSeconds(5));
        MigrationId id = new MigrationId("recover-1");
        FailoverCoordinator coordinator = new FailoverCoordinator(ownership, migrations, clock);
        coordinator.prepare(new MigrationRecord(id, resource, source, target, MigrationState.REQUESTED,
                StateVersion.initial(), clock.instant(), new MigrationCheckpointMetadata("room-2", "codec-a", true)));
        migrations.transition(id, MigrationState.FROZEN, StateVersion.initial(), clock.instant());
        migrations.transition(id, MigrationState.CHECKPOINTED, new StateVersion(2), clock.instant());
        clock.advance(Duration.ofSeconds(6));
        OwnershipLease recovered = coordinator.recover(id, Duration.ofSeconds(5));
        routes.publish(recovered, "rpc://target");
        assertEquals(MigrationState.COMMITTED, migrations.find(id).orElseThrow().state());
        assertEquals("rpc://target", routes.resolve(resource).orElseThrow().endpoint());
        assertFalse(routes.withdraw(sourceLease));
        assertTrue(routes.withdraw(recovered));
    }

    /** 不完整检查点不能用于恢复，避免缺失历史被误当成权威状态。 */
    @Test void recoveryRejectsIncompleteCheckpointHistory() {
        MutableClock clock = new MutableClock();
        InMemoryOwnershipStore ownership = new InMemoryOwnershipStore(clock);
        InMemoryMigrationStore migrations = new InMemoryMigrationStore();
        OwnershipResource resource = new OwnershipResource("entity", "incomplete", 1);
        MigrationId id = new MigrationId("incomplete-history");
        migrations.create(new MigrationRecord(id, resource, new ProcessIncarnation("source", "one"),
                new ProcessIncarnation("target", "one"), MigrationState.CHECKPOINTED, new StateVersion(1), clock.instant(),
                new MigrationCheckpointMetadata("room-2", "codec-a", false)));
        FailoverCoordinator coordinator = new FailoverCoordinator(ownership, migrations, clock);
        assertThrows(OwnershipException.class, () -> coordinator.recover(id, Duration.ofSeconds(1)));
    }

    @Test
    void capacityBudgetBoundsAndDrainsWithoutBackgroundThreads() throws Exception {
        CapacityBudget budget = new CapacityBudget(1, 1);
        CapacityBudget.Permit first = budget.tryAcquire().orElseThrow();
        assertTrue(budget.tryAcquire().isEmpty());
        assertThrows(CapacityRejectedException.class, () -> budget.acquire(Duration.ZERO));
        first.close();
        try (CapacityBudget.Permit second = budget.acquire(Duration.ofMillis(10))) {
            assertEquals(1, budget.activeCount());
            budget.drain();
            assertThrows(CapacityRejectedException.class, () -> budget.tryAcquire());
            assertFalse(budget.awaitDrained(Duration.ZERO));
        }
        assertEquals(0, budget.activeCount());
        assertTrue(budget.awaitDrained(Duration.ZERO));
    }

    @Test
    void capacityBoundStoreExposesSnapshotAndRejectsAfterDrain() {
        InMemoryOwnershipStore ownership = new InMemoryOwnershipStore();
        OwnershipResource resource = new OwnershipResource("state", "capacity", 1);
        OwnershipLease lease = ownership.acquire(resource, new ProcessIncarnation("p", "i"), Duration.ofMinutes(1));
        InMemoryFencedStateStore<String> delegate = new InMemoryFencedStateStore<>(ownership);
        CapacityBudget budget = new CapacityBudget(1, 0);
        CapacityBoundStateStore<String> bounded = new CapacityBoundStateStore<>(delegate, budget, Duration.ZERO);
        bounded.commit(new StateCommitRequest<>(resource, lease, StateVersion.initial(), "capacity-1", "ok"));
        assertEquals(0, bounded.budget().snapshot().active());
        budget.drain();
        assertThrows(CapacityRejectedException.class, () -> bounded.read(resource));
    }

    private static final class MutableClock extends Clock {
        private Instant instant = Instant.EPOCH;

        void advance(final Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public Instant instant() {
            return instant;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(final java.time.ZoneId zone) {
            return this;
        }
    }
}
