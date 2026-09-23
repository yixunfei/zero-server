package group.zn.zero.benchmark.performance;

import group.zn.zero.actor.ActorMessage;
import group.zn.zero.actor.LaneKey;
import group.zn.zero.actor.handler.ActorHandler;
import group.zn.zero.actor.scheduler.LocalActorScheduler;
import group.zn.zero.aoi.AoiEntity;
import group.zn.zero.aoi.InMemoryAoiIndex;
import group.zn.zero.aoi.Position;
import group.zn.zero.framesync.FrameInput;
import group.zn.zero.framesync.FrameMatchConfig;
import group.zn.zero.framesync.FrameMatchRuntime;
import group.zn.zero.scene.LocalSceneService;
import group.zn.zero.scene.SceneEnterRequest;
import group.zn.zero.scene.SceneLeaveRequest;

/** 长稳阶段循环创建/销毁游戏对象；不与 Actor Lane 共享可变状态。 @author zn */
final class GameResourceChurn {
    /** 保留轻量结果，避免基准中的无用计算被删除。 */
    private static volatile long checksum;
    private GameResourceChurn() { }
    /** 每轮释放场景、观察者和对局；关闭后不保留业务对象。 */
    static void cycle() {
        for (int iteration = 0; iteration < 10; iteration++) {
            try (var scheduler = new LocalActorScheduler(); var scene = new LocalSceneService(scheduler);
                    var match = new FrameMatchRuntime("churn", FrameMatchConfig.defaults(),
                            (frame, batch) -> checksum += batch.inputs().size(), event -> { }, event -> { }, scheduler)) {
                var aoi = new InMemoryAoiIndex();
                for (int i = 0; i < 64; i++) {
                    scene.enterScene(new SceneEnterRequest(i, "scene", "trace")).toCompletableFuture().join();
                    aoi.add(new AoiEntity("e" + i, new Position(i, i), 0, null));
                    match.submit(new FrameInput("u" + i, 1, 1, 0, new byte[64], "trace")).toCompletableFuture().join();
                }
                checksum += aoi.observe("observer", new Position(0, 0), 64).size();
                aoi.forgetObserver("observer");
                match.tick().toCompletableFuture().join();
                for (int i = 0; i < 64; i++) scene.leaveScene(new SceneLeaveRequest(i, "scene", "trace")).toCompletableFuture().join();
                // 缓存值不能反向经 handler→scheduler 钉住已销毁的解析快照。
                scheduler.register(CharSequence.class, ActorHandler.sync((context, message) -> checksum += scheduler.statistics().pending()));
                scheduler.dispatch(new ActorMessage(LaneKey.custom("cache-lifecycle"), "payload")).toCompletableFuture().join();
            }
        }
    }
}
