package group.zn.zero.ownership;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * 生产故障转移的显式流程编排器；不创建线程，也不绑定数据库、RPC 或消息系统。
 * 每一步都写入 MigrationStore，因此进程重启后可从 COMMITTED 或 ACTIVATED 继续。
 */
public final class FailoverCoordinator {
    private final OwnershipStore ownershipStore;
    private final MigrationStore migrationStore;
    private final Clock clock;

    /** 创建故障转移编排器。 */
    public FailoverCoordinator(
            final OwnershipStore ownershipStore,
            final MigrationStore migrationStore,
            final Clock clock) {
        this.ownershipStore = Objects.requireNonNull(ownershipStore, "ownershipStore");
        this.migrationStore = Objects.requireNonNull(migrationStore, "migrationStore");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** 使用 UTC 系统时钟创建故障转移编排器。 */
    public FailoverCoordinator(
            final OwnershipStore ownershipStore,
            final MigrationStore migrationStore) {
        this(ownershipStore, migrationStore, Clock.systemUTC());
    }

    /** 登记迁移请求；重复调用同一 migration id 幂等。 */
    public MigrationRecord prepare(final MigrationRecord record) {
        return migrationStore.create(record);
    }

    /** 将已完成 checkpoint 的迁移标记为可接管；可重复调用。 */
    public MigrationRecord commitCheckpoint(final MigrationId id, final StateVersion checkpointVersion) {
        MigrationRecord record = require(id);
        if (record.state() != MigrationState.CHECKPOINTED && record.state() != MigrationState.COMMITTED) {
            throw new OwnershipException("migration must be checkpointed before failover commit");
        }
        return migrationStore.transition(id, MigrationState.COMMITTED, checkpointVersion, clock.instant());
    }

    /** 接管已过期资源；若上次提交已成功但回包丢失，则返回目标节点当前有效 lease。 */
    public OwnershipLease claim(final MigrationId id, final Duration leaseDuration) {
        MigrationRecord record = require(id);
        if (record.state() != MigrationState.COMMITTED && record.state() != MigrationState.ACTIVATED) {
            throw new OwnershipException("migration is not committed for failover");
        }
        Optional<OwnershipLease> current = ownershipStore.current(record.resource(), clock.instant());
        if (current.isPresent() && current.orElseThrow().owner().equals(record.target())) {
            return current.orElseThrow();
        }
        return ownershipStore.claim(record.resource(), record.target(), leaseDuration);
    }

    /** 在权威 ownership 当前 lease 上执行激活 fencing。 */
    public MigrationRecord activate(final MigrationId id, final OwnershipLease lease) {
        MigrationRecord record = require(id);
        ownershipStore.assertCurrent(lease);
        OwnershipLease current = ownershipStore.current(record.resource(), clock.instant())
                .orElseThrow(() -> new OwnershipException("target ownership is not active"));
        if (!sameLease(current, lease) || !current.owner().equals(record.target())) {
            throw new OwnershipException("failover activation has a stale lease");
        }
        return migrationStore.transition(id, MigrationState.ACTIVATED, record.checkpointVersion(), clock.instant());
    }

    /**
     * 从持久迁移记录恢复故障转移；只允许从已 checkpoint 的阶段继续，返回目标当前 lease。
     * REQUESTED/FROZEN 必须由调用方先完成冻结与快照，避免恢复流程猜测业务状态。
     */
    public OwnershipLease recover(final MigrationId id, final Duration leaseDuration) {
        MigrationRecord record = require(id);
        if (record.state() == MigrationState.CHECKPOINTED) {
            commitCheckpoint(id, record.checkpointVersion());
        }
        MigrationRecord resumed = require(id);
        if (resumed.state() != MigrationState.COMMITTED && resumed.state() != MigrationState.ACTIVATED) {
            throw new OwnershipException("migration cannot recover before checkpoint commit");
        }
        return claim(id, leaseDuration);
    }

    /** 完成迁移并保留最终 checkpoint 版本。 */
    public MigrationRecord complete(final MigrationId id) {
        MigrationRecord record = require(id);
        return migrationStore.transition(id, MigrationState.COMPLETED, record.checkpointVersion(), clock.instant());
    }

    /** 显式终止故障转移；调用方负责记录告警和补偿。 */
    public MigrationRecord abort(final MigrationId id) {
        MigrationRecord record = require(id);
        return migrationStore.transition(id, MigrationState.ABORTED, record.checkpointVersion(), clock.instant());
    }

    private MigrationRecord require(final MigrationId id) {
        return migrationStore.find(Objects.requireNonNull(id, "id"))
                .orElseThrow(() -> new OwnershipException("migration does not exist"));
    }

    private static boolean sameLease(final OwnershipLease left, final OwnershipLease right) {
        return left != null && right != null && left.resource().equals(right.resource())
                && left.epoch() == right.epoch() && left.owner().equals(right.owner());
    }
}
