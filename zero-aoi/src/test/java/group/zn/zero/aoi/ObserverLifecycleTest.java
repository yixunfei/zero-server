package group.zn.zero.aoi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 候选工作区、失败重试、观察者生命周期及查询缓存回归。 @author zn */
class ObserverLifecycleTest {
    /** 相等但不同引用的可变状态仍沿用原比较语义，不能被场景序号快速路径掩盖。 */
    @Test void equalReplacementWithMutableStateRemainsObservable() {
        var index = new InMemoryAoiIndex();
        var center = new Position(0, 0);
        var original = new AoiEntity("e", center, 0, new java.util.ArrayList<>(List.of("same")));
        var nextState = new java.util.ArrayList<>(List.of("same"));
        var replacement = new AoiEntity("e", center, 0, nextState);
        index.add(original);
        index.observe("o", center, 1);
        index.update(replacement);
        assertTrue(index.observe("o", center, 1).isEmpty());
        nextState.set(0, "changed");
        var update = index.observe("o", center, 1);
        assertEquals(VisibilityEvent.Type.UPDATE, update.getFirst().type());
        assertSame(replacement, update.getFirst().entity());
        assertTrue(index.observe("o", center, 1).isEmpty());
    }

    /** 快速路径必须尊重中心/范围、删除重进和独立观察者序号。 */
    @Test void queryAndLifecycleChangesInvalidateObservation() {
        var index = new InMemoryAoiIndex(1);
        var entity = new AoiEntity("entity", new Position(-1, 0), 0, null);
        index.add(entity);
        var original = index.observe("a", new Position(0, 0), 1);
        assertTrue(index.observe("a", new Position(0, 0), 1).isEmpty());
        assertEquals(1, index.observe("b", new Position(0, 0), 1).getFirst().syncSeq());
        assertEquals(VisibilityEvent.Type.LEAVE, index.observe("a", new Position(0, 0), 0).getFirst().type());
        assertEquals(3, index.observe("a", new Position(-1, 0), 0).getFirst().syncSeq());
        index.remove("entity");
        assertSame(entity, index.observe("a", new Position(-1, 0), 0).getFirst().entity());
        index.add(entity);
        index.forgetObserver("a");
        assertEquals(1, index.observe("a", new Position(-1, 0), 0).getFirst().syncSeq());
        assertEquals(List.of(new VisibilityEvent(VisibilityEvent.Type.ENTER, "a", entity, 1, 1)), original);
    }

    /** 用户 state.equals 抛错后不提交观察快照；重试仍能得到全部更新。 */
    @Test void equalityFailureDoesNotConsumePendingUpdate() {
        var index = new InMemoryAoiIndex();
        var center = new Position(0, 0);
        index.add(new AoiEntity("a", center, 0, "old"));
        index.add(new AoiEntity("b", center, 0, "old"));
        index.observe("o", center, 2);
        index.update(new AoiEntity("a", center, 0, "new"));
        var state = new FailingState();
        index.update(new AoiEntity("b", center, 0, state));
        assertThrows(IllegalStateException.class, () -> index.observe("o", center, 2));
        state.fail = false;
        var result = index.observe("o", center, 2);
        assertEquals(List.of("a", "b"), result.stream().map(event -> event.entity().entityId()).toList());
        assertEquals(3, result.getFirst().syncSeq());
    }

    /** 极端坐标的大范围查询及代次重置仍能计算离开事件。 */
    @Test void generationResetPreservesLeaves() throws Exception {
        var state = new ObserverState();
        var field = ObserverState.class.getDeclaredField("generation");
        field.setAccessible(true);
        field.setLong(state, Long.MAX_VALUE);
        assertEquals(1, state.nextGeneration());
        var index = new InMemoryAoiIndex(1);
        index.add(new AoiEntity("min", new Position(Integer.MIN_VALUE, 0), 0, null));
        assertEquals(1, index.observe("o", new Position(-1, 0), Integer.MAX_VALUE).size());
        assertEquals(VisibilityEvent.Type.LEAVE, index.observe("o", new Position(Integer.MAX_VALUE, 0), 0).getFirst().type());
    }

    /** 可控失败的业务状态，触发 record 的 equals 调用。 @author zn */
    private static final class FailingState {
        /** 本次比较是否失败。 */
        private boolean fail = true;
        /** @param other 对方状态。 @return false；失败时抛出，模拟业务比较异常。 */
        @Override public boolean equals(final Object other) {
            if (fail) throw new IllegalStateException("state equality");
            return this == other;
        }
        /** @return 身份散列；无数据变更。 */
        @Override public int hashCode() { return System.identityHashCode(this); }
    }
}
