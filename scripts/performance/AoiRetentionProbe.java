import group.zn.zero.aoi.AoiEntity;
import group.zn.zero.aoi.InMemoryAoiIndex;
import group.zn.zero.aoi.Position;
import java.lang.management.ManagementFactory;
import java.lang.ref.Reference;
import java.util.ArrayList;
import java.util.List;

/** 显式本机保留内存探针；相同实体下比较观察前、观察后与 forgetObserver 后，不是长期泄漏证明。 @author zn */
public final class AoiRetentionProbe {
    private AoiRetentionProbe() { }
    /** @param args 不使用。 @throws InterruptedException 采样等待被中断；保留中断失败。 */
    public static void main(final String[] args) throws InterruptedException {
        List<InMemoryAoiIndex> scenes = new ArrayList<>();
        Position center = new Position(0, 0);
        for (int i = 0; i < 10; i++) {
            InMemoryAoiIndex scene = new InMemoryAoiIndex();
            for (int entity = 0; entity < 2000; entity++) scene.add(new AoiEntity("e" + entity, center, 0, null));
            scenes.add(scene);
        }
        long before = retained(scenes);
        for (InMemoryAoiIndex scene : scenes) {
            for (int observer = 0; observer < 16; observer++) scene.observe("o" + observer, center, 64);
        }
        long observed = retained(scenes);
        for (InMemoryAoiIndex scene : scenes) {
            for (int observer = 0; observer < 16; observer++) scene.forgetObserver("o" + observer);
        }
        long forgotten = retained(scenes);
        System.out.printf("{\"scenes\":10,\"entitiesPerScene\":2000,\"observersPerScene\":16,"
                + "\"beforeHeap\":%d,\"observedHeap\":%d,\"forgottenHeap\":%d}%n", before, observed, forgotten);
        Reference.reachabilityFence(scenes);
    }
    private static long retained(final Object keepAlive) throws InterruptedException {
        System.gc();
        Thread.sleep(100);
        System.gc();
        long used = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
        Reference.reachabilityFence(keepAlive);
        return used;
    }
}
