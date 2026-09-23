package group.zn.zero.world;
import java.util.Objects;
public record EntitySnapshot(String entityId,String worldId,String ownerShardId,double x,double y,long stateVersion,long routeEpoch) {
 public EntitySnapshot { Objects.requireNonNull(entityId); Objects.requireNonNull(worldId); Objects.requireNonNull(ownerShardId); }
 public static EntitySnapshot of(Entity e){return new EntitySnapshot(e.entityId(),e.worldId(),e.ownerShardId(),e.x(),e.y(),e.stateVersion(),e.routeEpoch());}
}
