package group.zn.zero.world;

import group.zn.zero.actor.ActorMessage;
import group.zn.zero.actor.LaneKey;
import group.zn.zero.actor.handler.ActorHandler;
import group.zn.zero.actor.scheduler.ActorScheduler;
import group.zn.zero.actor.scheduler.LocalActorScheduler;
import java.util.ArrayList;
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
 * Deterministic in-memory world slice.
 * @author zn
 */
public final class LocalWorldService {
    private final ActorScheduler scheduler;
    private final Consumer<WorldEvent> eventSink;
    private final WorldMetrics metrics;
    /** Serializes shared world state while preserving the external actor lanes. */
    private final Object stateLock = new Object();
    private final Map<String, World> worlds = new HashMap<>();
    private final Map<String, Shard> shards = new HashMap<>();
    private final Map<String, Entity> entities = new HashMap<>();
    private final Map<String, Migration> migrations = new HashMap<>();
    private final List<WorldEvent> events = new ArrayList<>();

    public LocalWorldService() { this(new LocalActorScheduler(), e -> { }, WorldMetrics.NOOP); }
    public LocalWorldService(ActorScheduler scheduler) { this(scheduler, e -> { }, WorldMetrics.NOOP); }
    public LocalWorldService(ActorScheduler scheduler, Consumer<WorldEvent> eventSink, WorldMetrics metrics) {
        this.scheduler = Objects.requireNonNull(scheduler); this.eventSink = Objects.requireNonNull(eventSink); this.metrics = Objects.requireNonNull(metrics);
        scheduler.register(Command.class, ActorHandler.sync((context, message) -> ((Command) message.payload()).run()));
    }
    public void createWorld(World world, List<Shard> initialShards) {
        rejectReentrantMutation();
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
    public void createWorld(String worldId, String initialShardId) { createWorld(new World(worldId, 0), List.of(new Shard(initialShardId, worldId, 0))); }
    public Entity enterWorld(String entityId, String worldId) { return call(entityLane(entityId), () -> { World w=world(worldId); String shard=shards.values().stream().filter(s->s.worldId().equals(worldId)).map(Shard::shardId).sorted().findFirst().orElseThrow(()->failure("no shard")); Entity e=new Entity(entityId,worldId,shard,0,0,0,w.routeEpoch(),EntityStatus.ACTIVE,null); if(entities.putIfAbsent(entityId,e)!=null) throw failure("entity exists"); emit(new WorldEvent(WorldEvent.Type.ENTERED,worldId,entityId,null,shard)); metrics.increment("enter","success","assigned"); return e; }); }
    public Entity moveEntity(String entityId,String shardId,double x,double y) { return call(entityLane(entityId), () -> { Entity e=entity(entityId); if(!e.ownerShardId().equals(shardId)||e.status()!=EntityStatus.ACTIVE||e.migrationId()!=null) { metrics.increment("move","rejected","not-owner"); throw failure("entity is not owned by shard"); } Entity moved=e.move(x,y); entities.put(entityId,moved); emit(new WorldEvent(WorldEvent.Type.MOVED,e.worldId(),entityId,shardId,shardId)); metrics.increment("move","success","owner"); return moved; }); }
    public Migration requestMigration(String migrationId,String entityId,String targetShardId) {
        long routeEpoch;
        synchronized (stateLock) {
            routeEpoch = world(entity(entityId).worldId()).routeEpoch();
        }
        return requestMigration(migrationId, entityId, targetShardId, routeEpoch);
    }
    /** 申请迁移；同实体只允许一个迁移，重试相同 ID 保持幂等，不允许跨 world。 */
    public Migration requestMigration(String migrationId, String entityId, String targetShardId, long routeEpoch) {
        return call(entityLane(entityId), () -> requestMigrationInLane(migrationId, entityId, targetShardId, routeEpoch));
    }

    /** Asynchronously locks the source entity for migration. */
    public CompletionStage<Migration> requestMigrationAsync(
            String migrationId, String entityId, String targetShardId) {
        long routeEpoch;
        synchronized (stateLock) {
            Entity current = entity(entityId);
            routeEpoch = world(current.worldId()).routeEpoch();
        }
        return callAsync(entityLane(entityId),
                () -> requestMigrationInLane(migrationId, entityId, targetShardId, routeEpoch));
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
            metrics.increment("migration", "success", "source-locked");
            return migration;
    }
    public Migration prepareMigration(String migrationId) { return prepareMigration(migrationId, true); }
    public Migration prepareMigration(String migrationId, boolean accepted) {
        return prepareMigrationAsync(migrationId, accepted).toCompletableFuture().join();
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
    public Migration commitMigration(String migrationId) {
        return call(entityLane(entityForMigration(migrationId)), () -> commitMigrationInLane(migrationId));
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

    public Migration releaseSource(String migrationId) {
        return call(entityLane(entityForMigration(migrationId)), () -> releaseSourceInLane(migrationId));
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
        metrics.increment("migration", "success", "completed");
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

    /**
     * Blocking compatibility facade. Prefer {@link #migrateAsync(String, String, String)} from actor or IO threads.
     *
     * @deprecated synchronous orchestration can block the calling executor
     */
    @Deprecated(forRemoval = false)
    public Migration migrate(String id, String entity, String target) {
        return migrateAsync(id, entity, target).toCompletableFuture().join();
    }
    public Optional<EntitySnapshot> queryEntity(String entityId) { synchronized (stateLock) { return Optional.ofNullable(entities.get(entityId)).map(EntitySnapshot::of); } }
    public EntitySnapshot snapshot(String entityId) { return queryEntity(entityId).orElseThrow(()->failure("entity not found")); }
    public Migration migration(String id) { synchronized (stateLock) { return migrations.get(id); } }
    public EntityStatus entityStatus(String id) { synchronized (stateLock) { return entity(id).status(); } }
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
    private <T> T call(LaneKey lane, Task<T> task){ return callAsync(lane, task).toCompletableFuture().join(); }
    private <T> CompletionStage<T> callAsync(LaneKey lane, Task<T> task) {
        rejectReentrantMutation();
        CompletableFuture<T> result = new CompletableFuture<>();
        try {
            CompletionStage<Void> dispatched = scheduler.dispatch(new ActorMessage(lane, new Command(() -> {
                try {
                    T value;
                    synchronized (stateLock) {
                        value = task.run();
                    }
                    result.complete(value);
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
            metrics.increment("migration", "failed", "prepare");
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
    private void rejectReentrantMutation() {
        if (Thread.holdsLock(stateLock)) throw failure("world callbacks cannot synchronously mutate world state");
    }
    private Entity requireMigrationOwner(Migration migration) {
        Entity current = entity(migration.entityId());
        if (current.status() != EntityStatus.MIGRATING_OUT
                || !migration.migrationId().equals(current.migrationId())
                || !migration.sourceShardId().equals(current.ownerShardId())) throw failure("stale migration owner");
        return current;
    }
    private void emit(WorldEvent e){ events.add(e); eventSink.accept(e); }
    private static IllegalStateException failure(String s){return new IllegalStateException(s);}
    @FunctionalInterface private interface Task<T>{T run();} private static final class Holder<T>{T value;} private record Command(Runnable action){void run(){action.run();}}
}
