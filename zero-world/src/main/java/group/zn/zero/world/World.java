package group.zn.zero.world;

import java.util.Objects;

public record World(String worldId, long routeEpoch) {
    public World { require(worldId, "worldId"); if (routeEpoch < 0) throw new IllegalArgumentException("routeEpoch"); }
    public World advanceRoute() { return new World(worldId, routeEpoch + 1); }
    private static void require(String value, String name) { Objects.requireNonNull(value, name); if (value.isBlank()) throw new IllegalArgumentException(name); }
}
