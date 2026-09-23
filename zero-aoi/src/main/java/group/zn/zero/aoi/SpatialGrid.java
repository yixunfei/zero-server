package group.zn.zero.aoi;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 单所有者二维均匀网格。所有操作均需由场景所有者串行调用。
 * 查询成本为访问格数加候选数及结果物化；密集热点仍可能退化为全量查询。
 * @author zn
 */
final class SpatialGrid {
    /** 每格边长，单位与 Position 一致。 */
    private final int cellSize;
    /** 已占用网格；桶内按实体 ID 寻址，空桶立即删除。 */
    private final Map<Long, Map<String, AoiEntity>> cells = new HashMap<>();

    /**
     * 创建网格，线程不安全。
     * @param cellSize 正数网格边长。
     * @throws IllegalArgumentException 边长不是正数。
     */
    SpatialGrid(final int cellSize) {
        if (cellSize <= 0) throw new IllegalArgumentException("cellSize must be positive");
        this.cellSize = cellSize;
    }

    /**
     * 新增或替换实体；调用方保证 ID 一致，线程不安全，变更网格。
     * @param prior 先前实体，新增时为空。
     * @param next 新实体；不可为空。
     */
    void put(final AoiEntity prior, final AoiEntity next) {
        long nextKey = key(next.position());
        if (prior != null && key(prior.position()) != nextKey) remove(prior);
        cells.computeIfAbsent(nextKey, ignored -> new HashMap<>()).put(next.entityId(), next);
    }

    /**
     * 移除已存在实体并回收空桶；线程不安全，变更网格。
     * @param entity 已存在的实体；不可为空。
     */
    void remove(final AoiEntity entity) {
        long key = key(entity.position());
        Map<String, AoiEntity> bucket = cells.get(key);
        bucket.remove(entity.entityId());
        if (bucket.isEmpty()) cells.remove(key);
    }

    /**
     * 查询闭区间 Chebyshev 范围；线程不安全，不变更网格。
     * @param center 中心；不可为空。
     * @param range 非负范围。
     * @return 不可变、无序、可能为空的 ID 快照；返回后可安全跨线程使用。
     * @throws NullPointerException 中心为空。
     * @throws IllegalArgumentException 范围为负数。
     */
    Set<String> visible(final Position center, final int range) {
        Set<String> result = new HashSet<>();
        visit(center, range, entity -> result.add(entity.entityId()));
        return result.isEmpty() ? Set.of() : Collections.unmodifiableSet(result);
    }

    /** 内部候选遍历；回调不得更改网格，线程不安全，不暴露桶或借用集合。 */
    void visit(final Position center, final int range, final Consumer<AoiEntity> consumer) {
        Objects.requireNonNull(center, "center");
        if (range < 0) throw new IllegalArgumentException("range < 0");
        if (cells.isEmpty()) return;
        long minX = cellBound((long) center.x() - range);
        long maxX = cellBound((long) center.x() + range);
        long minY = cellBound((long) center.y() - range);
        long maxY = cellBound((long) center.y() + range);
        // 用除法比较面积，防止 cellSize=1、极端 int 范围时乘法溢出。
        if (maxX - minX + 1 <= cells.size() / (maxY - minY + 1)) {
            for (long x = minX; x <= maxX; x++) {
                for (long y = minY; y <= maxY; y++) {
                    collect(cells.get(key((int) x, (int) y)), center, range, consumer);
                }
            }
        } else {
            // 极大或稀疏范围只遍历已占用格，避免枚举数十亿空格。
            for (Map.Entry<Long, Map<String, AoiEntity>> entry : cells.entrySet()) {
                long packed = entry.getKey();
                int x = (int) (packed >> 32);
                int y = (int) packed;
                if (x >= minX && x <= maxX && y >= minY && y <= maxY) {
                    collect(entry.getValue(), center, range, consumer);
                }
            }
        }
    }

    private long cellBound(final long coordinate) {
        long clamped = Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, coordinate));
        return Math.floorDiv(clamped, cellSize);
    }

    private long key(final Position position) {
        return key(Math.floorDiv(position.x(), cellSize), Math.floorDiv(position.y(), cellSize));
    }

    private static long key(final int x, final int y) {
        return ((long) x << 32) | (y & 0xffff_ffffL);
    }

    private static void collect(final Map<String, AoiEntity> bucket, final Position center,
            final int range, final Consumer<AoiEntity> consumer) {
        if (bucket == null) return;
        for (AoiEntity entity : bucket.values()) {
            Position position = entity.position();
            if (Math.abs((long) center.x() - position.x()) <= range
                    && Math.abs((long) center.y() - position.y()) <= range) {
                consumer.accept(entity);
            }
        }
    }
}
