package group.zn.zero.world;
import java.util.Objects;
/** 已提交的世界事件；事件消费者失败不撤销其对应状态。 @author zn */
public record WorldEvent(Type type,String worldId,String entityId,String sourceShardId,String targetShardId) {
 /** 事件类型；按实体 lane 顺序交付。 */
 public enum Type { ENTERED,MOVED,MIGRATION_SOURCE_LOCKED,MIGRATION_PREPARED,MIGRATION_COMMITTED,MIGRATION_RELEASED,MIGRATION_COMPLETED,MIGRATION_SOURCE_UNLOCKED,MIGRATION_ROLLED_BACK }
 public WorldEvent {Objects.requireNonNull(type);Objects.requireNonNull(worldId);}
}
