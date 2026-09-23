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
    /** 释放观察者状态；由所属场景串行调用，后续 observe 视为新观察者。 @param observerId 观察者 ID。 */
    default void forgetObserver(final String observerId) {
        // 旧自定义索引没有观察者缓存时无需处理；有状态实现应覆盖以释放资源。
    }
    long sceneSeq();
    record Result(Status status, long sceneSeq) {
        public enum Status { APPLIED, NOT_FOUND, ALREADY_EXISTS, INVALID }
    }
}
