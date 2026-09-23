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

/** Deterministic in-memory world slice. State changes are routed through world/shard actor lanes. */
public final class LocalWorldService {
    private final ActorScheduler scheduler;
    private final Consumer<WorldEvent> eventSink;
    private final WorldMetrics metrics;
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
    public synchronized void createWorld(World world, List<Shard> initialShards) {
        Objects.requireNonNull(world); if (worlds.putIfAbsent(world.worldId(), world) != null) throw failure("world exists");
        for (Shard shard : List.copyOf(initialShards)) { if (!world.worldId().equals(shard.worldId())) throw failure("shard world mismatch"); if (shards.putIfAbsent(shard.shardId(), shard) != null) throw failure("shard exists"); }
    }
    public synchronized void createWorld(String worldId, String initialShardId) { createWorld(new World(worldId, 0), List.of(new Shard(initialShardId, worldId, 0))); }
    public Entity enterWorld(String entityId, String worldId) { return call(LaneKey.custom(worldId), () -> { World w=world(worldId); String shard=shards.values().stream().filter(s->s.worldId().equals(worldId)).map(Shard::shardId).sorted().findFirst().orElseThrow(()->failure("no shard")); Entity e=new Entity(entityId,worldId,shard,0,0,0,w.routeEpoch(),EntityStatus.ACTIVE,null); if(entities.putIfAbsent(entityId,e)!=null) throw failure("entity exists"); emit(new WorldEvent(WorldEvent.Type.ENTERED,worldId,entityId,null,shard)); metrics.increment("enter","success","assigned"); return e; }); }
    public Entity moveEntity(String entityId,String shardId,double x,double y) { return call(LaneKey.custom(shardId), () -> { Entity e=entity(entityId); if(!e.ownerShardId().equals(shardId)||e.status()!=EntityStatus.ACTIVE) { metrics.increment("move","rejected","not-owner"); throw failure("entity is not owned by shard"); } Entity moved=e.move(x,y); entities.put(entityId,moved); emit(new WorldEvent(WorldEvent.Type.MOVED,e.worldId(),entityId,shardId,shardId)); metrics.increment("move","success","owner"); return moved; }); }
    public Migration requestMigration(String migrationId,String entityId,String targetShardId) { return requestMigration(migrationId,entityId,targetShardId,world(entity(entityId).worldId()).routeEpoch()); }
    public Migration requestMigration(String migrationId,String entityId,String targetShardId,long routeEpoch) { return call(LaneKey.custom(entity(entityId).ownerShardId()), () -> { Objects.requireNonNull(migrationId); Entity e=entity(entityId); Migration old=migrations.get(migrationId); if(old!=null){ if(!old.entityId().equals(entityId)||!old.targetShardId().equals(targetShardId)) throw failure("migration id conflict"); return old; } if(!shards.containsKey(targetShardId)) throw failure("target shard not found"); if(e.routeEpoch()!=routeEpoch) throw failure("stale route epoch"); Migration m=new Migration(migrationId,entityId,e.ownerShardId(),targetShardId,routeEpoch,MigrationState.SOURCE_LOCKED,EntitySnapshot.of(e)); entities.put(entityId,e.migrating(migrationId)); migrations.put(migrationId,m); emit(new WorldEvent(WorldEvent.Type.MIGRATION_SOURCE_LOCKED,e.worldId(),entityId,e.ownerShardId(),targetShardId)); metrics.increment("migration","success","source-locked"); return m; }); }
    public Migration prepareMigration(String migrationId) { return prepareMigration(migrationId, true); }
    public Migration prepareMigration(String migrationId, boolean accepted) { return call(LaneKey.custom(target(migrationId)), () -> { Migration m=findMigration(migrationId); if(m.state()==MigrationState.TARGET_PREPARED||m.state().ordinal()>=MigrationState.TARGET_COMMITTED.ordinal()) return m; if(!accepted){ migrations.put(migrationId,m.withState(MigrationState.ROLLBACK_SOURCE)); Entity e=entity(m.entityId()); entities.put(e.entityId(),new Entity(e.entityId(),e.worldId(),e.ownerShardId(),e.x(),e.y(),e.stateVersion(),e.routeEpoch(),EntityStatus.ACTIVE,null)); migrations.put(migrationId,m.withState(MigrationState.FAILED)); emit(new WorldEvent(WorldEvent.Type.MIGRATION_ROLLED_BACK,e.worldId(),e.entityId(),m.sourceShardId(),m.targetShardId())); metrics.increment("migration","failed","prepare"); return migrations.get(migrationId); } Migration n=m.withState(MigrationState.TARGET_PREPARED); migrations.put(migrationId,n); emit(new WorldEvent(WorldEvent.Type.MIGRATION_PREPARED,eWorld(m),m.entityId(),m.sourceShardId(),m.targetShardId())); return n; }); }
    public Migration commitMigration(String migrationId) { return call(LaneKey.custom(target(migrationId)), () -> { Migration m=findMigration(migrationId); if(m.state()==MigrationState.TARGET_COMMITTED||m.state()==MigrationState.SOURCE_RELEASED||m.state()==MigrationState.COMPLETED)return m; if(m.state()!=MigrationState.TARGET_PREPARED)throw failure("target not prepared"); Entity e=entity(m.entityId()); Entity n=e.transferred(m.targetShardId(),m.routeEpoch()); entities.put(e.entityId(),n); Migration result=m.withState(MigrationState.TARGET_COMMITTED); migrations.put(migrationId,result); emit(new WorldEvent(WorldEvent.Type.MIGRATION_COMMITTED,e.worldId(),e.entityId(),m.sourceShardId(),m.targetShardId())); return result; }); }
    public Migration releaseSource(String migrationId) { return call(LaneKey.custom(source(migrationId)), () -> { Migration m=findMigration(migrationId); if(m.state()==MigrationState.COMPLETED)return m; if(m.state()!=MigrationState.TARGET_COMMITTED)throw failure("target not committed"); Migration result=m.withState(MigrationState.COMPLETED); migrations.put(migrationId,result); emit(new WorldEvent(WorldEvent.Type.MIGRATION_COMPLETED,migrationWorld(m),m.entityId(),m.sourceShardId(),m.targetShardId())); metrics.increment("migration","success","completed"); return result; }); }
    public Migration migrate(String id,String entity,String target) { Migration requested=requestMigration(id,entity,target); Migration prepared=prepareMigration(requested.migrationId()); Migration committed=commitMigration(prepared.migrationId()); return releaseSource(committed.migrationId()); }
    public Optional<EntitySnapshot> queryEntity(String entityId) { return Optional.ofNullable(entities.get(entityId)).map(EntitySnapshot::of); }
    public EntitySnapshot snapshot(String entityId) { return queryEntity(entityId).orElseThrow(()->failure("entity not found")); }
    public Migration migration(String id) { return migrations.get(id); }
    public EntityStatus entityStatus(String id) { return entity(id).status(); }
    public List<WorldEvent> events() { return List.copyOf(events); }
    private World world(String id) { World world = worlds.get(id); if (world == null) throw failure("world not found"); return world; }
    private Entity entity(String id) { Entity entity = entities.get(id); if (entity == null) throw failure("entity not found"); return entity; }
    private String target(String id){return findMigration(id).targetShardId();} private String source(String id){return findMigration(id).sourceShardId();} private String migrationWorld(Migration m){return entity(m.entityId()).worldId();} private String eWorld(Migration m){return migrationWorld(m);}
    private Migration findMigration(String id){Migration m=migrations.get(id);if(m==null)throw failure("migration not found");return m;}
    private <T> T call(LaneKey lane, Task<T> task){ Holder<T> h=new Holder<>(); scheduler.dispatch(new ActorMessage(lane,new Command(()->h.value=task.run()))).toCompletableFuture().join(); return h.value; }
    private void emit(WorldEvent e){events.add(e);try{eventSink.accept(e);}catch(RuntimeException ignored){}}
    private static IllegalStateException failure(String s){return new IllegalStateException(s);}
    @FunctionalInterface private interface Task<T>{T run();} private static final class Holder<T>{T value;} private record Command(Runnable action){void run(){action.run();}}
}
