package group.zn.zero.aoi;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 单所有者内存 AOI。均匀网格筛选候选后按 Chebyshev 距离精确判断。
 * 所有操作线程不安全，必须由所属场景串行调用；快照结果不可变。
 * @author zn
 */
public final class InMemoryAoiIndex implements AoiIndex {
    /** 实体主表。 */
    private final Map<String, AoiEntity> entities = new HashMap<>();
    /** 观察者快照、原始序号及查询版本，由场景单所有者管理。 */
    private final Map<String, ObserverState> observed = new HashMap<>();
    /** 场景独占候选工作区；借出时置空，嵌套观察使用独立空间。 */
    private List<AoiEntity> candidates = new ArrayList<>();
    /** 与实体主表同步更新的位置索引。 */
    private final SpatialGrid grid;
    /** 场景成功变更序号。 */
    private long sceneSequence;

    /** 创建默认边长 64 的网格；线程不安全，单位与 Position 一致。 */
    public InMemoryAoiIndex() {
        this(64);
    }

    /**
     * 创建指定网格边长的索引；线程不安全。边长影响查询和维护成本，不改变可见性。
     * @param cellSize 正数边长，建议按常见视野范围与实体密度选择。
     * @throws IllegalArgumentException 边长不是正数。
     */
    public InMemoryAoiIndex(final int cellSize) {
        grid = new SpatialGrid(cellSize);
    }

    /**
     * 新增实体；仅成功时增加场景序号，线程不安全。
     * @param entity 实体；不可为空。
     * @return 不可变结果；重复 ID 返回 ALREADY_EXISTS。
     * @throws NullPointerException 实体为空。
     */
    @Override public Result add(final AoiEntity entity) {
        if (entities.containsKey(entity.entityId())) return new Result(Result.Status.ALREADY_EXISTS, sceneSequence);
        grid.put(null, entity);
        entities.put(entity.entityId(), entity);
        return new Result(Result.Status.APPLIED, ++sceneSequence);
    }
    /**
     * 更新状态与位置，并同步迁移网格；仅成功时增加序号，线程不安全。
     * @param entity 新状态；不可为空。
     * @return 不可变结果；不存在时返回 NOT_FOUND。
     * @throws NullPointerException 实体为空。
     */
    @Override public Result update(final AoiEntity entity) {
        AoiEntity prior = entities.get(entity.entityId());
        if (prior == null) return new Result(Result.Status.NOT_FOUND, sceneSequence);
        grid.put(prior, entity);
        entities.put(entity.entityId(), entity);
        return new Result(Result.Status.APPLIED, ++sceneSequence);
    }
    /**
     * 删除实体并清理空网格；仅成功时增加序号，线程不安全。
     * @param entityId 实体 ID。
     * @return 不可变结果；不存在时返回 NOT_FOUND。
     */
    @Override public Result remove(final String entityId) {
        AoiEntity removed = entities.remove(entityId);
        if (removed == null) return new Result(Result.Status.NOT_FOUND, sceneSequence);
        grid.remove(removed);
        return new Result(Result.Status.APPLIED, ++sceneSequence);
    }
    /**
     * 查询包含边界的方形视野；只读，线程不安全。
     * @param center 中心；不可为空。
     * @param range 非负 Chebyshev 距离。
     * @return 不可变、无序、可能为空的 ID 快照；返回后可安全跨线程使用。
     * @throws NullPointerException 中心为空。
     * @throws IllegalArgumentException 范围为负数。
     */
    @Override public Set<String> visible(final Position center, final int range) {
        return grid.visible(center, range);
    }
    /**
     * 计算可见性增量并更新观察者快照与同步序号；线程不安全。
     * @param observerId 观察者 ID。
     * @param center 中心；不可为空。
     * @param range 非负视野范围。
     * @return 不可变、可能为空的列表，按 ENTER/UPDATE/LEAVE 分组且组内按 ID 排序；
     *         集合可安全共享，实体 state 对象的线程安全性由业务保证。
     * @throws NullPointerException 中心为空。
     * @throws IllegalArgumentException 范围为负数。
     */
    @Override public List<VisibilityEvent> observe(final String observerId, final Position center, final int range) {
        java.util.Objects.requireNonNull(center, "center");
        if (range < 0) throw new IllegalArgumentException("range < 0");
        ObserverState state = observed.computeIfAbsent(observerId, ignored -> new ObserverState());
        if (state.unchanged(center, range, sceneSequence)) return List.of();
        long version = sceneSequence;
        List<AoiEntity> next = candidates == null ? new ArrayList<>() : candidates;
        candidates = null;
        try {
            grid.visit(center, range, next::add);
            return changes(observerId, center, range, state, version, next);
        } finally {
            int size = next.size();
            next.clear();
            // 一份有界工作区；大范围查询不得永久放大每个观察者的保留内存。
            if (candidates == null && size <= 4096) candidates = next;
        }
    }

    private List<VisibilityEvent> changes(final String observer, final Position center, final int range,
            final ObserverState state, final long version, final List<AoiEntity> next) {
        List<AoiEntity> entered = null;
        List<AoiEntity> updated = null;
        List<AoiEntity> left = null;
        AoiEntity firstEntered = null;
        AoiEntity firstUpdated = null;
        AoiEntity firstLeft = null;
        boolean stableIdentity = true;
        // equals 可能执行用户代码；先完成比较，再变更快照，异常不会吞掉尚未发送的事件。
        for (AoiEntity current : next) {
            ObserverState.Seen previous = state.entities.get(current.entityId());
            if (previous == null) {
                if (firstEntered == null) firstEntered = current;
                else entered = append(entered, current);
            } else if (!current.equals(previous.entity)) {
                if (firstUpdated == null) firstUpdated = current;
                else updated = append(updated, current);
            }
            else if (current != previous.entity) stableIdentity = false;
        }
        long generation = state.nextGeneration();
        for (AoiEntity current : next) {
            ObserverState.Seen previous = state.entities.get(current.entityId());
            if (previous != null) previous.generation = generation;
        }
        var iterator = state.entities.values().iterator();
        while (iterator.hasNext()) {
            ObserverState.Seen entry = iterator.next();
            if (entry.generation != generation) {
                if (firstLeft == null) firstLeft = entry.entity;
                else left = append(left, entry.entity);
                iterator.remove();
            }
        }
        state.commit(firstEntered, entered, generation);
        state.commit(firstUpdated, updated, generation);
        state.center = center;
        state.range = range;
        state.sceneSequence = version;
        state.stableIdentity = stableIdentity;
        int count = count(firstEntered, entered) + count(firstUpdated, updated) + count(firstLeft, left);
        if (count == 0) return List.of();
        if (count == 1) {
            AoiEntity entity = firstEntered != null ? firstEntered : firstUpdated != null ? firstUpdated : firstLeft;
            VisibilityEvent.Type type = firstEntered != null ? VisibilityEvent.Type.ENTER
                    : firstUpdated != null ? VisibilityEvent.Type.UPDATE : VisibilityEvent.Type.LEAVE;
            return List.of(new VisibilityEvent(type, observer, entity, sceneSequence, ++state.syncSequence));
        }
        List<VisibilityEvent> result = new ArrayList<>(count);
        appendEvents(result, firstEntered, entered, VisibilityEvent.Type.ENTER, observer, state);
        appendEvents(result, firstUpdated, updated, VisibilityEvent.Type.UPDATE, observer, state);
        appendEvents(result, firstLeft, left, VisibilityEvent.Type.LEAVE, observer, state);
        return List.copyOf(result);
    }

    private static List<AoiEntity> append(final List<AoiEntity> list, final AoiEntity entity) {
        List<AoiEntity> result = list == null ? new ArrayList<>() : list;
        result.add(entity);
        return result;
    }

    private static int count(final AoiEntity first, final List<AoiEntity> rest) {
        return first == null ? 0 : 1 + (rest == null ? 0 : rest.size());
    }

    private void appendEvents(final List<VisibilityEvent> result, final AoiEntity first, final List<AoiEntity> entities,
            final VisibilityEvent.Type type, final String observer, final ObserverState state) {
        if (first == null) return;
        if (entities == null) {
            result.add(new VisibilityEvent(type, observer, first, sceneSequence, ++state.syncSequence));
            return;
        }
        entities.add(first);
        entities.sort(java.util.Comparator.comparing(AoiEntity::entityId));
        for (AoiEntity entity : entities) {
            result.add(new VisibilityEvent(type, observer, entity, sceneSequence, ++state.syncSequence));
        }
    }

    /**
     * 释放离开的观察者快照与序号，幂等；须由所属 Scene Lane 调用。
     * @param observerId 观察者 ID。
     */
    @Override public void forgetObserver(final String observerId) {
        observed.remove(observerId);
    }

    /** @return 当前场景变更序号；只读，线程不安全。 */
    @Override public long sceneSeq() { return sceneSequence; }
}
