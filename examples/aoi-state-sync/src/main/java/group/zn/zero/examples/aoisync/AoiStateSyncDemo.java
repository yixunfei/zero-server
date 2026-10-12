package group.zn.zero.examples.aoisync;

import group.zn.zero.aoi.AoiEntity;
import group.zn.zero.aoi.InMemoryAoiIndex;
import group.zn.zero.aoi.Position;
import group.zn.zero.aoi.VisibilityEvent;
import group.zn.zero.statesync.InMemoryStateSync;
import group.zn.zero.statesync.SyncEnvelope;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

/**
 * 单场景 owner 上的 AOI 可见性与同步基线示例。
 * 状态同步模块校验信封的基线/序号，不替业务应用 payload；本例不创建网络或线程。
 * @author zn
 */
public final class AoiStateSyncDemo {
    private AoiStateSyncDemo() { }

    /** @param args 未使用参数。同步执行并输出结果；失败向上抛出，不修改外部数据。 */
    public static void main(final String[] args) {
        System.out.println(runDemo());
    }

    /** @return 不可空摘要；每次使用独立本地状态，调用间线程安全。失败抛 IllegalStateException。 */
    public static String runDemo() {
        DemoResult result = runFlow();
        return "aoi-state-sync=ok|mode=local|sceneSeq=" + result.delta().stateVersion()
                + "|visible=" + result.entered().size()
                + "|delta=APPLIED|stale=RESYNC_REQUIRED|recovery=APPLIED|duplicate=IGNORED";
    }

    /**
     * 顺序执行快照 → 更新 → 增量 → 错基线 → 全量恢复 → 重复消息，最后释放观察者。
     * @return 不可空、不可变的信封及 AOI 事件快照；无外部状态变化。
     * @throws IllegalStateException 模块处理结果不符合示例不变量。
     */
    public static DemoResult runFlow() {
        InMemoryAoiIndex aoi = new InMemoryAoiIndex(16);
        InMemoryStateSync sync = new InMemoryStateSync(64);
        Position observer = new Position(0, 0);
        Map<String, AoiEntity> scene = new HashMap<>();
        scene.put("player-1", new AoiEntity("player-1", new Position(2, 1), 1, Map.of("hp", 100)));
        scene.put("npc-1", new AoiEntity("npc-1", new Position(20, 20), 1, Map.of("kind", "guard")));
        scene.values().forEach(aoi::add);
        List<VisibilityEvent> entered = aoi.observe("observer-1", observer, 5);
        SyncEnvelope snapshot = new SyncEnvelope("scene-1", "observer-1", 1, 0, aoi.sceneSeq(),
                SyncEnvelope.Kind.SNAPSHOT, Map.of("entities", visibleEntities(aoi, scene, observer)));
        check(sync.accept(snapshot).status(), InMemoryStateSync.Status.APPLIED);

        // 先建立版本 2 基线，再改变实体；业务 payload 使用可规范化的 Map/List 基础类型。
        AoiEntity moved = new AoiEntity("player-1", new Position(3, 1), 2, Map.of("hp", 95));
        scene.put(moved.entityId(), moved);
        aoi.update(moved);
        List<VisibilityEvent> changed = aoi.observe("observer-1", observer, 5);
        SyncEnvelope delta = new SyncEnvelope("scene-1", "observer-1", 2, snapshot.stateVersion(),
                aoi.sceneSeq(), SyncEnvelope.Kind.DELTA,
                Map.of("upserts", project(changed.stream().map(VisibilityEvent::entity).toList())));
        check(sync.accept(delta).status(), InMemoryStateSync.Status.APPLIED);
        SyncEnvelope stale = new SyncEnvelope("scene-1", "observer-1", 3, 999, aoi.sceneSeq(),
                SyncEnvelope.Kind.DELTA, Map.of("upserts", List.of()));
        check(sync.accept(stale).status(), InMemoryStateSync.Status.RESYNC_REQUIRED);
        SyncEnvelope recovery = new SyncEnvelope("scene-1", "observer-1", 4, 0, aoi.sceneSeq(),
                SyncEnvelope.Kind.SNAPSHOT, Map.of("entities", visibleEntities(aoi, scene, observer)));
        check(sync.accept(recovery).status(), InMemoryStateSync.Status.APPLIED);
        check(sync.accept(recovery).status(), InMemoryStateSync.Status.IGNORED);
        aoi.forgetObserver("observer-1");
        sync.remove("scene-1", "observer-1");
        return new DemoResult(entered, changed, snapshot, delta, recovery);
    }

    private static List<Map<String, Object>> project(final List<AoiEntity> entities) {
        return entities.stream().map(entity -> Map.<String, Object>of(
                "id", entity.entityId(), "x", entity.position().x(), "y", entity.position().y(),
                "version", entity.stateVersion(), "state", entity.state())).toList();
    }

    private static List<Map<String, Object>> visibleEntities(final InMemoryAoiIndex aoi,
            final Map<String, AoiEntity> scene, final Position observer) {
        return project(aoi.visible(observer, 5).stream().sorted().map(scene::get).toList());
    }

    private static void check(final InMemoryStateSync.Status actual, final InMemoryStateSync.Status expected) {
        if (actual != expected) {
            throw new IllegalStateException("state-sync expected " + expected + ", got " + actual);
        }
    }

    /**
     * 可检查的本地流程结果。
     * @param entered 首次进入事件，不可变、有序、可能为空。
     * @param changed 更新事件，不可变、有序、可能为空。
     * @param snapshot 变更前快照。
     * @param delta 基于旧版本生成的增量。
     * @param recovery 错基线后的恢复快照。
     */
    public record DemoResult(List<VisibilityEvent> entered, List<VisibilityEvent> changed,
            SyncEnvelope snapshot, SyncEnvelope delta, SyncEnvelope recovery) {
        /** 防御性复制事件列表；空引用抛 NullPointerException，无外部变更。 */
        public DemoResult {
            entered = List.copyOf(entered);
            changed = List.copyOf(changed);
        }
    }
}
