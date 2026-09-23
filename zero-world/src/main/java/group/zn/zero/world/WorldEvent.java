package group.zn.zero.world;
import java.util.Objects;
public record WorldEvent(Type type,String worldId,String entityId,String sourceShardId,String targetShardId) {
 public enum Type { ENTERED,MOVED,MIGRATION_SOURCE_LOCKED,MIGRATION_PREPARED,MIGRATION_COMMITTED,MIGRATION_RELEASED,MIGRATION_COMPLETED,MIGRATION_ROLLED_BACK }
 public WorldEvent {Objects.requireNonNull(type);Objects.requireNonNull(worldId);}
}
