package group.zn.zero.world;
import java.util.Objects;
/** 不可变迁移记录；标识、归属令牌和源快照共同约束状态推进。 @author zn */
public record Migration(long sequence, String migrationId, String entityId, String sourceShardId,
        String targetShardId, long routeEpoch, MigrationState state, EntitySnapshot snapshot) {
 public Migration {
  if (sequence <= 0) throw new IllegalArgumentException("sequence must be positive");
  Objects.requireNonNull(migrationId);Objects.requireNonNull(entityId);Objects.requireNonNull(sourceShardId);
  Objects.requireNonNull(targetShardId);Objects.requireNonNull(state);
 }
 /** 兼容旧构造器；生产请求应显式传入单调序号。 */
 public Migration(String migrationId, String entityId, String sourceShardId, String targetShardId,
         long routeEpoch, MigrationState state, EntitySnapshot snapshot) {
  this(1, migrationId, entityId, sourceShardId, targetShardId, routeEpoch, state, snapshot);
 }
 public Migration withState(MigrationState next){return new Migration(sequence,migrationId,entityId,sourceShardId,targetShardId,routeEpoch,next,snapshot);}
}
