package group.zn.zero.world;
import java.util.Objects;
public record Migration(String migrationId,String entityId,String sourceShardId,String targetShardId,long routeEpoch,MigrationState state,EntitySnapshot snapshot) {
 public Migration { Objects.requireNonNull(migrationId);Objects.requireNonNull(entityId);Objects.requireNonNull(sourceShardId);Objects.requireNonNull(targetShardId);Objects.requireNonNull(state); }
 public Migration withState(MigrationState next){return new Migration(migrationId,entityId,sourceShardId,targetShardId,routeEpoch,next,snapshot);}
}
