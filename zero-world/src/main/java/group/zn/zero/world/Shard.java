package group.zn.zero.world;

import java.util.Objects;

/** 不可变世界分片配置；routeEpoch 为非负路由代际。 @author zn */
public record Shard(String shardId, String worldId, long routeEpoch) {
    public Shard { require(shardId, "shardId"); require(worldId, "worldId"); if (routeEpoch < 0) throw new IllegalArgumentException("routeEpoch"); }
    private static void require(String value, String name) { Objects.requireNonNull(value, name); if (value.isBlank()) throw new IllegalArgumentException(name); }
}
