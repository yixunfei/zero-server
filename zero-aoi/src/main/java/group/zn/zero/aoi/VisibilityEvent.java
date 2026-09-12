package group.zn.zero.aoi;

/** Visibility transition for an observer. */
public record VisibilityEvent(Type type, String observerId, AoiEntity entity, long sceneSeq, long syncSeq) {
    public enum Type { ENTER, UPDATE, LEAVE }
}
