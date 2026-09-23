package group.zn.zero.aoi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** 差分观察事件与朴素有序参考模型对照；覆盖观察者离开后重新加入。 @author zn */
class ObserveModelTest {
    @Test void randomizedSnapshotsPreserveGroupingIdsAndSequences() { compareObservers(1); }

    /** 多观察者分别持有历史，不得共享代次、快照或同步序号。 */
    @Test void multipleObserversMatchIndependentReferenceModels() { compareObservers(16); }

    private void compareObservers(final int observers) {
        var index = new InMemoryAoiIndex();
        Map<String, AoiEntity> all = new HashMap<>();
        List<Map<String, AoiEntity>> histories = new ArrayList<>();
        for (int i = 0; i < observers; i++) histories.add(new HashMap<>());
        Random random = new Random(923);
        long[] sequences = new long[observers];
        for (int step = 0; step < 3000; step++) {
            int observerIndex = step % observers;
            String observer = "observer" + observerIndex;
            Map<String, AoiEntity> prior = histories.get(observerIndex);
            String id = "e" + random.nextInt(100);
            var entity = new AoiEntity(id, new Position(random.nextInt(100), random.nextInt(100)), step, null);
            if (step % 5 == 0) { index.remove(id); all.remove(id); }
            else if (all.put(id, entity) == null) index.add(entity);
            else index.update(entity);
            Position center = new Position(random.nextInt(100), random.nextInt(100));
            Map<String, AoiEntity> next = new HashMap<>();
            for (AoiEntity value : all.values()) {
                if (Math.abs(value.position().x() - center.x()) <= 30 && Math.abs(value.position().y() - center.y()) <= 30) {
                    next.put(value.entityId(), value);
                }
            }
            List<VisibilityEvent> expected = new ArrayList<>();
            for (VisibilityEvent.Type type : VisibilityEvent.Type.values()) {
                List<String> selected = switch (type) {
                    case ENTER -> next.keySet().stream().filter(key -> !prior.containsKey(key)).sorted().toList();
                    case UPDATE -> next.keySet().stream().filter(prior::containsKey)
                            .filter(key -> !next.get(key).equals(prior.get(key))).sorted().toList();
                    case LEAVE -> prior.keySet().stream().filter(key -> !next.containsKey(key)).sorted().toList();
                };
                for (String key : selected) expected.add(new VisibilityEvent(type, observer,
                        type == VisibilityEvent.Type.LEAVE ? prior.get(key) : next.get(key), index.sceneSeq(), ++sequences[observerIndex]));
            }
            assertEquals(expected, index.observe(observer, center, 30));
            assertTrue(index.observe(observer, center, 30).isEmpty());
            prior.clear(); prior.putAll(next);
            if (step % 71 == 0) { index.forgetObserver(observer); prior.clear(); sequences[observerIndex] = 0; }
        }
    }
}
