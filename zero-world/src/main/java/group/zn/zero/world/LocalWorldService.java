package group.zn.zero.world;

import group.zn.zero.actor.ActorMessage;
import group.zn.zero.actor.LaneKey;
import group.zn.zero.actor.handler.ActorHandler;
import group.zn.zero.actor.scheduler.ActorScheduler;
import group.zn.zero.actor.scheduler.LocalActorScheduler;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * 单进程世界服务；同实体的移动和全部迁移阶段在 entity lane 串行。
 * 修改入口不等待其他消息；调用方不得在 Actor 内等待同 lane 返回的 future。
 * 外部事件和指标在状态提交后、状态锁外执行；失败通过完成信号报告，状态不回滚。
 * @author zn
 */
public final class LocalWorldService {
    /** 仅保留最近事件，避免长期运行的世界服务无限增长。 */
    private static final int MAX_EVENT_HISTORY = 4096;
    /** Actor 调度器；修改只在 entity lane 执行。 */
    private final ActorScheduler scheduler;
    /** 外部事件观察者；必须短时、非阻塞，不得等待当前 lane 后续消息。 */
    private final Consumer<WorldEvent> eventSink;
    /** 外部指标观察者；在状态锁外调用。 */
    private final WorldMetrics metrics;
    /** Serializes shared world state while preserving the external actor lanes. */
    private final Object stateLock = new Object();
    /** 世界配置，受 stateLock 保护。 */
    private final Map<String, World> worlds = new HashMap<>();
    /** 分片配置，受 stateLock 保护。 */
    private final Map<String, Shard> shards = new HashMap<>();
    /** 实体状态，受 stateLock 保护。 */
    private final Map<String, Entity> entities = new HashMap<>();
    /** 迁移状态，受 stateLock 保护。 */
    private final Map<String, Migration> migrations = new HashMap<>();
    /** 已提交事件历史，受 stateLock 保护。 */
    private final ArrayDeque<WorldEvent> events = new ArrayDeque<>();

    /** 创建直接推进的本地服务；消费者必须非阻塞。 */
    public LocalWorldService() { this(new LocalActorScheduler(), e -> { }, WorldMetrics.NOOP); }
    /** @param scheduler 外部调度器；线程安全，不创建线程。 */
    public LocalWorldService(ActorScheduler scheduler) { this(scheduler, e -> { }, WorldMetrics.NOOP); }
    /** @param scheduler 调度器。 @param eventSink 事件观察者。 @param metrics 指标观察者。 */
    public LocalWorldService(ActorScheduler scheduler, Consumer<WorldEvent> eventSink, WorldMetrics metrics) {
        this.scheduler = Objects.requireNonNull(scheduler); this.eventSink = Objects.requireNonNull(eventSink); this.metrics = Objects.requireNonNull(metrics);
        scheduler.register(Command.class, ActorHandler.sync((context, message) -> ((Command) message.payload()).run()));
    }
    /** 创建不可变世界配置；线程安全，重复 ID 或分片归属非法时拒绝。
     * @param world 世界。 @param initialShards 初始分片；不会保留可变列表。 */
    public void createWorld(World world, List<Shard> initialShards) {
        synchronized (stateLock) {
            Objects.requireNonNull(world);
            List<Shard> checked = List.copyOf(initialShards);
            if (worlds.containsKey(world.worldId())) throw failure("world exists");
            java.util.Set<String> ids = new java.util.HashSet<>();
            for (Shard shard : checked) {
                if (!world.worldId().equals(shard.worldId())) throw failure("shard world mismatch");
                if (shards.containsKey(shard.shardId()) || !ids.add(shard.shardId())) throw failure("shard exists");
            }
            worlds.put(world.worldId(), world);
            checked.forEach(shard -> shards.put(shard.shardId(), shard));
        }
    }
    /** @param worldId 世界 ID。 @param initialShardId 初始分片 ID；线程安全。 */
    public void createWorld(String worldId, String initialShardId) { createWorld(new World(worldId, 0), List.of(new Shard(initialShardId, worldId, 0))); }
    /** 进入世界；线程安全，异步分配分片并发布事件。
     * @param entityId 实体 ID。 @param worldId 世界 ID。 @return 分配后的不可变实体。 */
    public CompletionStage<Entity> enterWorldAsync(String entityId, String worldId) {
        return callAsync(entityLane(entityId), () -> {
            World world = world(worldId);
            String shard = shards.values().stream()
                    .filter(candidate -> candidate.worldId().equals(worldId))
                    .map(Shard::shardId).sorted().findFirst().orElseThrow(() -> failure("no shard"));
            Entity entity = new Entity(entityId, worldId, shard, 0, 0, 0,
                    world.routeEpoch(), EntityStatus.ACTIVE, null);
            if (entities.putIfAbsent(entityId, entity) != null) throw failure("entity exists");
            emit(new WorldEvent(WorldEvent.Type.ENTERED, worldId, entityId, null, shard));
            recordMetric("enter", "success", "assigned");
            return entity;
        });
    }
    /** 移动归属实体；线程安全，迁移中或非 owner 时异步失败。
     * @param entityId 实体 ID。 @param shardId owner 分片。 @param x 横坐标。 @param y 纵坐标。
     * @return 已提交的不可变实体。 */
    public CompletionStage<Entity> moveEntityAsync(String entityId, String shardId, double x, double y) {
        return callAsync(entityLane(entityId), () -> {
            Entity current = entity(entityId);
            if (!current.ownerShardId().equals(shardId) || current.status() != EntityStatus.ACTIVE
                    || current.migrationId() != null) {
                recordMetric("move", "rejected", "not-owner");
                throw failure("entity is not owned by shard");
            }
            Entity moved = current.move(x, y);
            entities.put(entityId, moved);
            emit(new WorldEvent(WorldEvent.Type.MOVED, current.worldId(), entityId, shardId, shardId));
            recordMetric("move", "success", "owner");
            return moved;
        });
    }
    /** Asynchronously locks the source entity for migration. */
    public CompletionStage<Migration> requestMigrationAsync(
            String migrationId, String entityId, String targetShardId) {
        return callAsync(entityLane(entityId), () -> requestMigrationInLane(migrationId, entityId,
                targetShardId, world(entity(entityId).worldId()).routeEpoch()));
    }

    /** Asynchronously locks the source entity using an explicit route epoch. */
    public CompletionStage<Migration> requestMigrationAsync(
            String migrationId, String entityId, String targetShardId, long routeEpoch) {
        return callAsync(entityLane(entityId),
                () -> requestMigrationInLane(migrationId, entityId, targetShardId, routeEpoch));
    }

    private Migration requestMigrationInLane(
            String migrationId, String entityId, String targetShardId, long routeEpoch) {
            Objects.requireNonNull(migrationId);
            Entity current = entity(entityId);
            Migration old = migrations.get(migrationId);
            if (old != null) {
                if (!old.entityId().equals(entityId) || !old.targetShardId().equals(targetShardId)
                        || old.routeEpoch() != routeEpoch) throw failure("migration id conflict");
                return old;
            }
            Shard targetShard = shards.get(targetShardId);
            if (targetShard == null) throw failure("target shard not found");
            if (!targetShard.worldId().equals(current.worldId())) throw failure("target world mismatch");
            if (current.status() != EntityStatus.ACTIVE || current.migrationId() != null) {
                throw failure("entity migration already in progress");
            }
            if (current.routeEpoch() != routeEpoch) throw failure("stale route epoch");
            Migration migration = new Migration(migrationId, entityId, current.ownerShardId(), targetShardId,
                    routeEpoch, MigrationState.SOURCE_LOCKED, EntitySnapshot.of(current));
            entities.put(entityId, current.migrating(migrationId));
            migrations.put(migrationId, migration);
            emit(new WorldEvent(WorldEvent.Type.MIGRATION_SOURCE_LOCKED, current.worldId(), entityId,
                    current.ownerShardId(), targetShardId));
            recordMetric("migration", "success", "source-locked");
            return migration;
    }
    /**
     * Asynchronously prepares a migration without blocking the caller or an actor lane.
     *
     * @param migrationId migration identity; non-blank
     * @param accepted whether the target accepts the handoff
     * @return completion signal containing the resulting migration
     */
    public CompletionStage<Migration> prepareMigrationAsync(String migrationId, boolean accepted) {
        return callAsync(entityLane(entityForMigration(migrationId)), () -> prepareMigrationInLane(migrationId, accepted));
    }
    /** @return asynchronous accepted target preparation. */
    public CompletionStage<Migration> prepareMigrationAsync(String migrationId) {
        return prepareMigrationAsync(migrationId, true);
    }

    /** Asynchronously commits a prepared target. */
    public CompletionStage<Migration> commitMigrationAsync(String migrationId) {
        return callAsync(entityLane(entityForMigration(migrationId)), () -> commitMigrationInLane(migrationId));
    }

    private Migration commitMigrationInLane(String migrationId) {
        Migration m = findMigration(migrationId);
        if (m.state() == MigrationState.TARGET_COMMITTED
                || m.state() == MigrationState.SOURCE_RELEASED
                || m.state() == MigrationState.COMPLETED) {
            return m;
        }
        if (m.state() != MigrationState.TARGET_PREPARED) {
            throw failure("target not prepared");
        }
        Entity e = requireMigrationOwner(m);
        // 保留本次交接令牌直到源释放，期间不得启动第二次迁移或移动。
        Entity n = new Entity(e.entityId(), e.worldId(), m.targetShardId(), e.x(), e.y(),
                e.stateVersion() + 1, m.routeEpoch(), EntityStatus.ACTIVE, m.migrationId());
        entities.put(e.entityId(), n);
        Migration result = m.withState(MigrationState.TARGET_COMMITTED);
        migrations.put(migrationId, result);
        emit(new WorldEvent(WorldEvent.Type.MIGRATION_COMMITTED, e.worldId(), e.entityId(),
                m.sourceShardId(), m.targetShardId()));
        return result;
    }


    /** Asynchronously releases the source after target commit. */
    public CompletionStage<Migration> releaseSourceAsync(String migrationId) {
        return callAsync(entityLane(entityForMigration(migrationId)), () -> releaseSourceInLane(migrationId));
    }

    private Migration releaseSourceInLane(String migrationId) {
        Migration m = findMigration(migrationId);
        if (m.state() == MigrationState.COMPLETED) {
            return m;
        }
        if (m.state() != MigrationState.TARGET_COMMITTED) {
            throw failure("target not committed");
        }
        Entity current = entity(m.entityId());
        if (!m.targetShardId().equals(current.ownerShardId())
                || current.status() != EntityStatus.ACTIVE
                || !m.migrationId().equals(current.migrationId())
                || current.routeEpoch() != m.routeEpoch()
                || current.stateVersion() != m.snapshot().stateVersion() + 1) {
            throw failure("stale migration release");
        }
        entities.put(current.entityId(), new Entity(current.entityId(), current.worldId(),
                current.ownerShardId(), current.x(), current.y(), current.stateVersion(),
                current.routeEpoch(), EntityStatus.ACTIVE, null));
        Migration result = m.withState(MigrationState.COMPLETED);
        migrations.put(migrationId, result);
        emit(new WorldEvent(WorldEvent.Type.MIGRATION_COMPLETED, migrationWorld(m), m.entityId(),
                m.sourceShardId(), m.targetShardId()));
        recordMetric("migration", "success", "completed");
        return result;
    }
    /**
     * Runs the complete migration without synchronously waiting between actor stages.
     *
     * @param id migration identity; non-blank
     * @param entity entity identity; non-blank
     * @param target target shard identity; non-blank
     * @return completion signal containing the completed migration
     */
    public CompletionStage<Migration> migrateAsync(String id, String entity, String target) {
        return requestMigrationAsync(id, entity, target)
                .thenCompose(requested -> prepareMigrationAsync(requested.migrationId()))
                .thenCompose(prepared -> commitMigrationAsync(prepared.migrationId()))
                .thenCompose(committed -> releaseSourceAsync(committed.migrationId()));
    }

    /** 线程安全地读取不可变快照；空表示不存在。 */
    public Optional<EntitySnapshot> queryEntity(String entityId) { synchronized (stateLock) { return Optional.ofNullable(entities.get(entityId)).map(EntitySnapshot::of); } }
    /** 线程安全地读取不可变快照；空表示不存在。 */
    /** 线程安全地读取快照；不存在时抛出 IllegalStateException。 */
    public EntitySnapshot snapshot(String entityId) { return queryEntity(entityId).orElseThrow(()->failure("entity not found")); }
    /** 线程安全地读取迁移；不存在返回 null。 */
    public Migration migration(String id) { synchronized (stateLock) { return migrations.get(id); } }
    /** 线程安全地读取状态；不存在时抛出 IllegalStateException。 */
    public EntityStatus entityStatus(String id) { synchronized (stateLock) { return entity(id).status(); } }
    /** 返回不可变、提交有序、可能为空的最近事件快照；线程安全，最多保留 4096 条。 */
    public List<WorldEvent> events() { synchronized (stateLock) { return List.copyOf(events); } }
    private World world(String id) { World world = worlds.get(id); if (world == null) throw failure("world not found"); return world; }
    private Entity entity(String id) { Entity entity = entities.get(id); if (entity == null) throw failure("entity not found"); return entity; }
    private String entityForMigration(String migrationId) {
        synchronized (stateLock) { return findMigration(migrationId).entityId(); }
    }
    private LaneKey entityLane(String entityId) {
        return LaneKey.entity(Objects.requireNonNull(entityId, "entityId"));
    }
    private String migrationWorld(Migration m){return entity(m.entityId()).worldId();} private String eWorld(Migration m){return migrationWorld(m);}
    private Migration findMigration(String id){Migration m=migrations.get(id);if(m==null)throw failure("migration not found");return m;}
    private <T> CompletionStage<T> callAsync(LaneKey lane, Task<T> task) {
        CompletableFuture<T> result = new CompletableFuture<>();
        try {
            CompletionStage<Void> dispatched = scheduler.dispatch(new ActorMessage(lane, new Command(() -> {
                try {
                    result.complete(execute(task));
                } catch (RuntimeException | Error failure) {
                    result.completeExceptionally(failure);
                    throw failure;
                }
            })));
            dispatched.whenComplete((ignored, failure) -> {
                if (failure != null) {
                    result.completeExceptionally(failure);
                }
            });
        } catch (RuntimeException | Error failure) {
            result.completeExceptionally(failure);
        }
        return result;
    }

    private Migration prepareMigrationInLane(String migrationId, boolean accepted) {
        Migration m = findMigration(migrationId);
        if (m.state() == MigrationState.TARGET_PREPARED || isCommitted(m.state())) {
            return m;
        }
        if (m.state() == MigrationState.FAILED || m.state() == MigrationState.SOURCE_UNLOCKED) {
            return m;
        }
        requireMigrationOwner(m);
        if (!accepted) {
            migrations.put(migrationId, m.withState(MigrationState.ROLLBACK_SOURCE));
            Entity e = entity(m.entityId());
            Entity unlocked = new Entity(e.entityId(), e.worldId(), e.ownerShardId(), e.x(), e.y(),
                    e.stateVersion(), e.routeEpoch(), EntityStatus.ACTIVE, null);
            entities.put(e.entityId(), unlocked);
            migrations.put(migrationId, m.withState(MigrationState.SOURCE_UNLOCKED));
            emit(new WorldEvent(WorldEvent.Type.MIGRATION_SOURCE_UNLOCKED, e.worldId(), e.entityId(),
                    m.sourceShardId(), m.targetShardId()));
            Migration failed = m.withState(MigrationState.FAILED);
            migrations.put(migrationId, failed);
            emit(new WorldEvent(WorldEvent.Type.MIGRATION_ROLLED_BACK, e.worldId(), e.entityId(),
                    m.sourceShardId(), m.targetShardId()));
            recordMetric("migration", "failed", "prepare");
            return failed;
        }
        Migration prepared = m.withState(MigrationState.TARGET_PREPARED);
        migrations.put(migrationId, prepared);
        emit(new WorldEvent(WorldEvent.Type.MIGRATION_PREPARED, eWorld(m), m.entityId(),
                m.sourceShardId(), m.targetShardId()));
        return prepared;
    }

    private boolean isCommitted(MigrationState state) {
        return state == MigrationState.TARGET_COMMITTED
                || state == MigrationState.SOURCE_RELEASED
                || state == MigrationState.COMPLETED;
    }
    private Entity requireMigrationOwner(Migration migration) {
        Entity current = entity(migration.entityId());
        if (current.status() != EntityStatus.MIGRATING_OUT
                || !migration.migrationId().equals(current.migrationId())
                || !migration.sourceShardId().equals(current.ownerShardId())) throw failure("stale migration owner");
        return current;
    }
    /** 当前命令的待通知观察者；只在 stateLock 内收集，锁外按顺序调用。 */
    private List<Runnable> pendingNotifications;

    private void emit(WorldEvent event) {
        events.addLast(event);
        if (events.size() > MAX_EVENT_HISTORY) {
            events.removeFirst();
        }
        pendingNotifications.add(() -> eventSink.accept(event));
    }

    private void recordMetric(String operation, String result, String reason) {
        pendingNotifications.add(() -> metrics.increment(operation, result, reason));
    }

    /** 状态提交 -> 释放锁 -> 通知；通知失败不撤销已提交状态，也不漏掉后续通知。 */
    private <T> T execute(Task<T> task) {
        T value = null;
        Throwable failure = null;
        List<Runnable> notifications;
        synchronized (stateLock) {
            pendingNotifications = new ArrayList<>();
            try {
                value = task.run();
            } catch (RuntimeException | Error cause) {
                failure = cause;
            } finally {
                notifications = pendingNotifications;
                pendingNotifications = null;
            }
        }
        for (Runnable notification : notifications) {
            try {
                notification.run();
            } catch (RuntimeException | Error cause) {
                if (failure == null) failure = cause;
                else if (failure != cause) failure.addSuppressed(cause);
            }
        }
        if (failure instanceof RuntimeException cause) throw cause;
        if (failure instanceof Error cause) throw cause;
        return value;
    }
    private static IllegalStateException failure(String s){return new IllegalStateException(s);}
    /** lane 内的状态操作。 */
    @FunctionalInterface private interface Task<T> { T run(); }
    /** 不携带跨 lane 可变状态的调度消息。 */
    private record Command(Runnable action) { void run() { action.run(); } }
}
