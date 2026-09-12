package group.zn.zero.aoi;

/** Immutable AOI entity state. */
public record AoiEntity(String entityId, Position position, long stateVersion, Object state) {
    public AoiEntity {
        if (entityId == null || entityId.isBlank()) throw new IllegalArgumentException("entityId is blank");
        if (position == null) throw new NullPointerException("position");
        if (stateVersion < 0) throw new IllegalArgumentException("stateVersion < 0");
    }
}
