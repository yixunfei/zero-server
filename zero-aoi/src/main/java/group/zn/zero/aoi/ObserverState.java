package group.zn.zero.aoi;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 单所有者观察状态，序号不装箱；内部记录从不作为业务事件暴露。 @author zn */
final class ObserverState {
    /** 上次完成观察的实体快照。 */
    final Map<String, Seen> entities = new HashMap<>();
    /** 已发出的事件序号。 */
    long syncSequence;
    /** 已观察的场景版本；初始不可命中。 */
    long sceneSequence = -1;
    /** 上次查询中心；初始为空。 */
    Position center;
    /** 上次查询范围。 */
    int range;
    /** 所有相等状态也使用同一实体引用；否则可变 state 的值比较可能在未更新索引时改变。 */
    boolean stableIdentity;
    /** 内部遍历代次；与业务序号无关。 */
    private long generation;

    boolean unchanged(final Position position, final int distance, final long version) {
        return stableIdentity && version == sceneSequence && distance == range && position.equals(center);
    }

    long nextGeneration() {
        if (generation == Long.MAX_VALUE) {
            for (Seen entry : entities.values()) entry.generation = 0;
            generation = 0;
        }
        return ++generation;
    }

    void commit(final AoiEntity first, final List<AoiEntity> changes, final long currentGeneration) {
        if (first != null) commit(first, currentGeneration);
        if (changes == null) return;
        for (AoiEntity entity : changes) commit(entity, currentGeneration);
    }

    private void commit(final AoiEntity entity, final long currentGeneration) {
        Seen seen = entities.get(entity.entityId());
        if (seen == null) entities.put(entity.entityId(), new Seen(entity, currentGeneration));
        else {
            seen.entity = entity;
            seen.generation = currentGeneration;
        }
    }

    /** 代次可变但实体引用仍为独立不可变 record；不池化业务对象。 @author zn */
    static final class Seen {
        /** 上次可见状态，供 LEAVE 使用。 */
        AoiEntity entity;
        /** 最近出现代次。 */
        long generation;
        private Seen(final AoiEntity entity, final long generation) {
            this.entity = entity;
            this.generation = generation;
        }
    }
}
