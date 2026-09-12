package group.zn.zero.aoi;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Minimal AOI index contract. */
public interface AoiIndex {
    Result add(AoiEntity entity);
    Result update(AoiEntity entity);
    Result remove(String entityId);
    Set<String> visible(Position center, int range);
    List<VisibilityEvent> observe(String observerId, Position center, int range);
    long sceneSeq();
    record Result(Status status, long sceneSeq) {
        public enum Status { APPLIED, NOT_FOUND, ALREADY_EXISTS, INVALID }
    }
}
