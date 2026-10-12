package group.zn.zero.examples.recipes.npc;

import group.zn.zero.core.config.MapZeroConfig;
import group.zn.zero.core.error.SystemErrorCode;
import group.zn.zero.core.error.ZeroException;
import group.zn.zero.npc.BehaviorDecision;
import group.zn.zero.npc.BehaviorState;
import group.zn.zero.npc.LocalNpcZone;
import group.zn.zero.npc.NoopNpcMetrics;
import group.zn.zero.npc.NpcAuditEvent;
import group.zn.zero.npc.NpcEvent;
import group.zn.zero.npc.NpcErrorCode;
import group.zn.zero.npc.NpcId;
import group.zn.zero.npc.NpcLifecycleState;
import group.zn.zero.npc.NpcSnapshot;
import group.zn.zero.npc.TickBudget;
import group.zn.zero.npc.TickResult;
import group.zn.zero.npc.ZoneActor;
import group.zn.zero.runtime.actor.ActorRuntime;
import group.zn.zero.runtime.assembly.RuntimeComposition;
import group.zn.zero.runtime.assembly.RuntimeProfile;
import group.zn.zero.runtime.bootstrap.RuntimeBasics;
import group.zn.zero.runtime.bootstrap.ZeroRuntimeExecutors;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * 正式 zero-npc 接入：创建 → 有界决策 → 失败事件 → 移除。
 * 状态与观察者均在区域 lane 执行；决策和观察者不得阻塞或等待同 lane。
 * 通知失败不会回滚已提交状态；命令行线程只等待结果和读取快照。
 * @author zn
 */
public final class NpcExample {
    private NpcExample() { }

    /**
     * 打印生命周期及预算结果；每次运行独占区域。
     * @param args 未使用。
     * @throws Exception 异步操作失败、超时或线程中断。
     */
    public static void main(final String[] args) throws Exception {
        System.out.println("npc=" + runFlow());
    }

    /**
     * 演示重复创建拒绝、次数预算和行为失败隔离；通知列表只在完成后读取。
     * @return 非空不可变结果；线程安全，不暴露可变区域。
     * @throws Exception 非预期框架失败、五秒等待超时或线程中断。
     */
    public static Result runFlow() throws Exception {
        var events = new ArrayList<NpcEvent>();
        var audits = new ArrayList<NpcAuditEvent>();
        try (var runtime = RuntimeComposition.builder(RuntimeProfile.local())
                .install(RuntimeBasics.module(new MapZeroConfig(Map.of()),
                        () -> ZeroRuntimeExecutors.localPrototype("recipe-npc", 2)))
                .install(ActorRuntime.module()).build()) {
            var zone = new LocalNpcZone(new ZoneActor("village", runtime.require(ActorRuntime.ACTOR_SCHEDULER)),
                    (npc, context) -> {
                        if (npc.templateId().equals("broken")) {
                            throw ZeroException.of(SystemErrorCode.SYSTEM_ERROR);
                        }
                        return new BehaviorDecision(new BehaviorState("patrol"), "本地巡逻决策");
                    }, events::add, audits::add, new NoopNpcMetrics());
            runtime.start();
            var guard = created("guard");
            for (var npc : java.util.List.of(guard, created("merchant"), created("broken"))) {
                zone.spawnAsync(npc).toCompletableFuture().get(5, TimeUnit.SECONDS);
            }
            String duplicateCode = duplicateSpawnCode(zone, guard);
            // 本例只演示次数上限，避免机器负载影响结果；实际游戏应再设置帧时间预算。
            var limited = zone.tickAsync(new TickBudget(Long.MAX_VALUE, 1), "tick-limited")
                    .toCompletableFuture().get(5, TimeUnit.SECONDS);
            var complete = zone.tickAsync(new TickBudget(Long.MAX_VALUE, 3), "tick-complete")
                    .toCompletableFuture().get(5, TimeUnit.SECONDS);
            boolean patrol = zone.find(guard.npcId()).orElseThrow().behavior().type().equals("patrol");
            zone.despawnAsync(guard.npcId(), "remove-guard").toCompletableFuture().get(5, TimeUnit.SECONDS);
            long failures = events.stream().filter(event -> event.type() == NpcEvent.Type.BEHAVIOR_FAILED).count();
            return new Result(limited, complete, duplicateCode, failures, patrol,
                    zone.find(guard.npcId()).isEmpty(), zone.size(), audits.size());
        }
    }

    private static NpcSnapshot created(final String id) {
        return new NpcSnapshot(new NpcId(id), id, "village", "scene-1", "village",
                NpcLifecycleState.CREATED, BehaviorState.idle(), 0, 0, 0, "spawn-" + id);
    }

    private static String duplicateSpawnCode(final LocalNpcZone zone, final NpcSnapshot npc) throws Exception {
        try {
            zone.spawnAsync(npc).toCompletableFuture().get(5, TimeUnit.SECONDS);
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof ZeroException zero && zero.errorCode() == NpcErrorCode.ALREADY_EXISTS) {
                return zero.errorCode().code();
            }
            throw failure;
        }
        throw new IllegalStateException("重复创建必须被拒绝");
    }

    /**
     * 不可变区域摘要；线程安全。
     * @param limited 次数预算耗尽时的结果。
     * @param complete 全量决策结果；processed 包含失败的决策。
     * @param duplicateCode 重复创建的框架错误码。
     * @param behaviorFailures 失败事件数。
     * @param patrol 守卫是否进入巡逻。
     * @param removed 守卫是否已移除。
     * @param remaining 剩余 NPC 数。
     * @param audits 创建及移除审计数。
     * @author zn
     */
    public record Result(TickResult limited, TickResult complete, String duplicateCode, long behaviorFailures,
                         boolean patrol, boolean removed, int remaining, int audits) { }
}
