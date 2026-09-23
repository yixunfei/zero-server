package group.zn.zero.aoi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 网格 AOI 与朴素空间语义的差分和边界测试。 @author zn */
class GridAoiIndexTest {
    /** 随机新增、跨格更新、删除和失败写入均与全量参考模型一致。 */
    @Test
    void mutationsAndQueriesMatchBruteForceAcrossCellSizes() {
        for (int cellSize : new int[] {1, 7, 64, Integer.MAX_VALUE}) {
            var index = new InMemoryAoiIndex(cellSize);
            Map<String, AoiEntity> reference = new HashMap<>();
            var random = new Random(91827);
            long sequence = 0;
            for (int i = 0; i < 2000; i++) {
                String id = "e" + random.nextInt(100);
                Position position = position(random);
                AoiEntity entity = new AoiEntity(id, position, i, "v" + i);
                boolean present = reference.containsKey(id);
                AoiIndex.Result result;
                boolean applied;
                switch (random.nextInt(3)) {
                    case 0 -> {
                        result = index.add(entity);
                        applied = !present;
                        if (applied) reference.put(id, entity);
                        assertEquals(applied ? AoiIndex.Result.Status.APPLIED : AoiIndex.Result.Status.ALREADY_EXISTS, result.status());
                    }
                    case 1 -> {
                        result = index.update(entity);
                        applied = present;
                        if (applied) reference.put(id, entity);
                        assertEquals(applied ? AoiIndex.Result.Status.APPLIED : AoiIndex.Result.Status.NOT_FOUND, result.status());
                    }
                    default -> {
                        result = index.remove(id);
                        applied = present;
                        reference.remove(id);
                        assertEquals(applied ? AoiIndex.Result.Status.APPLIED : AoiIndex.Result.Status.NOT_FOUND, result.status());
                    }
                }
                if (applied) sequence++;
                assertEquals(sequence, result.sceneSeq());
                assertEquals(sequence, index.sceneSeq());
                Position center = i % 2 == 0 ? position : position(random);
                int range = i % 17 == 0 ? Integer.MAX_VALUE : random.nextInt(200);
                assertEquals(bruteForce(reference, center, range), index.visible(center, range));
            }
        }
    }

    /** 覆盖负数落格、闭区间边界与极端范围；超大查询不能枚举全部空格。 */
    @Test
    void handlesExtremeCoordinatesAndReturnsIndependentImmutableSnapshots() {
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            var index = new InMemoryAoiIndex(1);
            Map<String, AoiEntity> reference = new HashMap<>();
            for (int x : new int[] {Integer.MIN_VALUE, -65, -64, -1, 0, 63, 64, Integer.MAX_VALUE}) {
                var entity = new AoiEntity(Integer.toString(x), new Position(x, x), 0, null);
                reference.put(entity.entityId(), entity);
                index.add(entity);
            }
            for (Position center : List.of(new Position(0, 0), new Position(Integer.MIN_VALUE, Integer.MAX_VALUE))) {
                for (int range : new int[] {0, 1, 64, Integer.MAX_VALUE}) {
                    assertEquals(bruteForce(reference, center, range), index.visible(center, range));
                }
            }
            Set<String> snapshot = index.visible(new Position(0, 0), 0);
            index.remove("0");
            assertEquals(Set.of("0"), snapshot);
            assertThrows(UnsupportedOperationException.class, () -> snapshot.add("other"));
            assertTrue(index.visible(new Position(0, 0), 0).isEmpty());
        });
        assertThrows(IllegalArgumentException.class, () -> new InMemoryAoiIndex(0));
        assertThrows(IllegalArgumentException.class, () -> new InMemoryAoiIndex(-1));
        assertThrows(IllegalArgumentException.class, () -> new InMemoryAoiIndex().visible(new Position(0, 0), -1));
        assertThrows(NullPointerException.class, () -> new InMemoryAoiIndex().visible(null, 1));
    }

    /** 跨格后保持增量事件分组、ID 顺序和单观察者序号，删除实体保留 LEAVE 身份。 */
    @Test
    void observationDeltasKeepDeterministicOrderAndVersions() {
        var index = new InMemoryAoiIndex(2);
        index.add(new AoiEntity("z", new Position(-1, -1), 0, null));
        index.add(new AoiEntity("b", new Position(0, 0), 0, null));
        assertEquals(List.of("b", "z"), index.observe("o", new Position(0, 0), 4).stream()
                .map(event -> event.entity().entityId()).toList());
        index.update(new AoiEntity("b", new Position(3, 3), 1, "changed"));
        index.remove("z");
        index.add(new AoiEntity("a", new Position(-4, -4), 0, null));
        var events = index.observe("o", new Position(0, 0), 4);
        assertEquals(List.of(VisibilityEvent.Type.ENTER, VisibilityEvent.Type.UPDATE, VisibilityEvent.Type.LEAVE),
                events.stream().map(VisibilityEvent::type).toList());
        assertEquals(List.of("a", "b", "z"), events.stream().map(event -> event.entity().entityId()).toList());
        assertEquals(List.of(3L, 4L, 5L), events.stream().map(VisibilityEvent::syncSeq).toList());
        assertTrue(events.stream().allMatch(event -> event.sceneSeq() == index.sceneSeq()));
        assertTrue(index.observe("o", new Position(0, 0), 4).isEmpty());
    }

    private Position position(final Random random) {
        return random.nextInt(8) == 0 ? new Position(random.nextInt(), random.nextInt())
                : new Position(random.nextInt(500) - 250, random.nextInt(500) - 250);
    }

    private Set<String> bruteForce(final Map<String, AoiEntity> entities, final Position center, final int range) {
        Set<String> result = new HashSet<>();
        for (AoiEntity entity : entities.values()) {
            if (Math.max(Math.abs((long) center.x() - entity.position().x()),
                    Math.abs((long) center.y() - entity.position().y())) <= range) result.add(entity.entityId());
        }
        return result;
    }
}
