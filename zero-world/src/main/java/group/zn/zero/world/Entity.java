package group.zn.zero.world;

import java.util.Objects;

public record Entity(String entityId, String worldId, String ownerShardId, double x, double y, long stateVersion,
                     long routeEpoch, EntityStatus status, String migrationId) {
    public Entity { require(entityId,"entityId"); require(worldId,"worldId"); require(ownerShardId,"ownerShardId"); Objects.requireNonNull(status,"status"); if(stateVersion<0||routeEpoch<0) throw new IllegalArgumentException("version"); }
    public Entity move(double nx,double ny) { return new Entity(entityId,worldId,ownerShardId,nx,ny,stateVersion+1,routeEpoch,status,migrationId); }
    public Entity migrating(String id) { return new Entity(entityId,worldId,ownerShardId,x,y,stateVersion,routeEpoch,EntityStatus.MIGRATING_OUT,id); }
    public Entity transferred(String shard,long epoch) { return new Entity(entityId,worldId,shard,x,y,stateVersion+1,epoch,EntityStatus.ACTIVE,null); }
    private static void require(String v,String n){Objects.requireNonNull(v,n);if(v.isBlank())throw new IllegalArgumentException(n);}
}
