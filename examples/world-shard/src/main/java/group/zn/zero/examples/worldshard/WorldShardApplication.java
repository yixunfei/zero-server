package group.zn.zero.examples.worldshard;

import group.zn.zero.world.EntitySnapshot;
import group.zn.zero.world.LocalWorldService;
import group.zn.zero.world.Migration;

/** Deterministic local world/shard migration example. */
public final class WorldShardApplication {
    private WorldShardApplication() { }

    public static void main(final String[] args) {
        System.out.println(run().marker());
    }

    public static Result run() {
        LocalWorldService service = new LocalWorldService();
        service.createWorld("world-1", "shard-a");
        service.createWorld("world-2", "shard-b");
        EntitySnapshot entered = EntitySnapshot.of(service.enterWorld("entity-1", "world-1"));
        service.moveEntity("entity-1", entered.ownerShardId(), 10, 20);
        Migration migration = service.migrate("migration-1", "entity-1", "shard-b");
        EntitySnapshot moved = service.snapshot("entity-1");
        Migration replay = service.migration("migration-1");
        return new Result(moved, migration.state().name(), replay.state().name(), service.events().size());
    }

    public record Result(EntitySnapshot entity, String migrationState, String replayState, int events) {
        public String marker() {
            return "world-shard=ok|mode=local|owner=" + entity.ownerShardId()
                    + "|stateVersion=" + entity.stateVersion() + "|routeEpoch=" + entity.routeEpoch()
                    + "|migration=" + migrationState + "|replay=" + replayState
                    + "|events=" + events + "|productionReady=false";
        }
    }
}
