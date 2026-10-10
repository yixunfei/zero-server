package group.zn.zero.npc;

import group.zn.zero.actor.ActorMessage;
import group.zn.zero.core.error.ZeroException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * 区域状态修改全部投递到 zone lane；查询返回不可变快照。
 * 决策与通知在状态锁外、当前 lane 上执行，必须短时且非阻塞。
 * 回调可以异步提交后续命令，但不得等待同 lane；通知失败不撤销已提交状态。
 * @author zn
 */
public final class LocalNpcZone {
    /** 受管区域 lane。 */
    private final ZoneActor actor;
    /** 状态短锁，仅保护查询和状态提交，不跨越外部回调。 */
    private final Object stateLock = new Object();
    /** 区域快照表，受 stateLock 保护。 */
    private final Map<NpcId, NpcSnapshot> npcs = new LinkedHashMap<>();
    /** 有界同步决策。 */
    private final BehaviorAdapter adapter;
    /** 提交后的事件观察者。 */
    private final NpcEventSink events;
    /** 提交后的审计观察者。 */
    private final NpcAuditSink audit;
    /** 区域指标观察者。 */
    private final NpcMetrics metrics;

    /** @param actor 区域 lane。 @param adapter 同步决策。 @param events 事件观察者。
     * @param audit 审计观察者。 @param metrics 指标观察者；均不可为空。 */
    public LocalNpcZone(final ZoneActor actor, final BehaviorAdapter adapter, final NpcEventSink events,
            final NpcAuditSink audit, final NpcMetrics metrics) {
        this.actor = Objects.requireNonNull(actor, "actor");
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.events = Objects.requireNonNull(events, "events");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        NpcDispatchRegistry.register(actor.scheduler());
    }
    /** 激活 NPC；线程安全，重复或非法状态异步失败，通知失败不回滚。
     * @param npc CREATED 快照。 @return 只读 ACTIVE 完成信号；不可为空。 */
    public CompletionStage<NpcSnapshot> spawnAsync(final NpcSnapshot npc) {
        Objects.requireNonNull(npc, "npc");
        return submit(npc.traceId(), () -> spawn(npc));
    }
    /** 移除 NPC；线程安全，不存在异步失败。
     * @param id NPC 身份。 @param trace 链路标识。 @return 只读 REMOVED 完成信号。 */
    public CompletionStage<NpcSnapshot> despawnAsync(final NpcId id, final String trace) {
        Objects.requireNonNull(id, "id");
        return submit(trace, () -> despawn(id, trace));
    }
    /** 有界区域 tick；任一预算为零则不调用决策，普通决策异常隔离，Error 异步失败。
     * @param budget 时间/操作预算。 @param trace 链路标识。 @return 只读完成信号，线程安全。 */
    public CompletionStage<TickResult> tickAsync(final TickBudget budget, final String trace) {
        Objects.requireNonNull(budget, "budget");
        return submit(trace, () -> tick(budget, trace));
    }
    /** @param id NPC 身份。 @return 不可变快照，可能为空，线程安全。 */
    public Optional<NpcSnapshot> find(final NpcId id) {
        synchronized (stateLock) { return Optional.ofNullable(npcs.get(Objects.requireNonNull(id, "id"))); }
    }
    /** @return 区域对象数；线程安全，不修改状态。 */
    public int size() { synchronized (stateLock) { return npcs.size(); } }

    private <T> CompletionStage<T> submit(final String trace, final Supplier<T> operation) {
        Objects.requireNonNull(trace, "trace");
        var value = new AtomicReference<T>();
        try {
            return actor.scheduler().dispatch(new ActorMessage(actor.laneKey(), trace,
                    new NpcDispatchRegistry.Command(() -> value.set(operation.get()))))
                    .thenApply(ignored -> value.get()).toCompletableFuture().minimalCompletionStage();
        } catch (RuntimeException | Error failure) { return CompletableFuture.failedFuture(failure); }
    }
    private NpcSnapshot spawn(final NpcSnapshot npc) {
        NpcSnapshot active;
        synchronized (stateLock) {
            if (npc.lifecycle() != NpcLifecycleState.CREATED) throw failure(NpcErrorCode.INVALID_STATE);
            if (npcs.containsKey(npc.npcId())) throw failure(NpcErrorCode.ALREADY_EXISTS);
            active = new NpcSnapshot(npc.npcId(), npc.templateId(), actor.zoneId(), npc.sceneId(),
                    actor.zoneId(), NpcLifecycleState.ACTIVE, npc.behavior(), npc.version() + 1,
                    npc.spawnedAt(), npc.lastTickAt(), npc.traceId());
            npcs.put(active.npcId(), active);
        }
        var notifications = new NpcNotifications();
        notifications.attempt(() -> events.publish(event(active, NpcEvent.Type.SPAWNED, active.traceId())));
        notifications.attempt(() -> audit.record(new NpcAuditEvent("spawn", actor.zoneId(), active.npcId(), active.traceId())));
        notifications.attempt(metrics::recordSpawn);
        notifications.finish();
        return active;
    }
    private NpcSnapshot despawn(final NpcId id, final String trace) {
        NpcSnapshot removed;
        synchronized (stateLock) {
            NpcSnapshot current = npcs.get(id);
            if (current == null) throw failure(NpcErrorCode.NOT_FOUND);
            removed = new NpcSnapshot(current.npcId(), current.templateId(), current.zoneId(),
                    current.sceneId(), current.ownerActorId(), NpcLifecycleState.REMOVED, current.behavior(),
                    current.version() + 1, current.spawnedAt(), current.lastTickAt(), trace);
            npcs.remove(id);
        }
        var notifications = new NpcNotifications();
        notifications.attempt(() -> events.publish(event(removed, NpcEvent.Type.DESPAWNED, trace)));
        notifications.attempt(() -> audit.record(new NpcAuditEvent("despawn", actor.zoneId(), id, trace)));
        notifications.attempt(metrics::recordDespawn);
        notifications.finish();
        return removed;
    }
    private TickResult tick(final TickBudget budget, final String trace) {
        List<NpcSnapshot> candidates;
        synchronized (stateLock) { candidates = new ArrayList<>(npcs.values()); }
        var notifications = new NpcNotifications();
        int done = 0;
        long start = System.nanoTime();
        for (NpcSnapshot snapshot : candidates) {
            if (done >= budget.budgetOps() || (System.nanoTime() - start) / 1_000_000 >= budget.budgetMillis()) break;
            try { decide(snapshot, done + 1, trace, notifications); }
            catch (Error failure) { notifications.record(failure); break; }
            done++;
        }
        int skipped = candidates.size() - done;
        TickResult.Outcome outcome = skipped == 0 ? TickResult.Outcome.COMPLETED
                : (done == 0 ? TickResult.Outcome.SKIPPED : TickResult.Outcome.DEGRADED);
        var result = new TickResult(actor.zoneId(), candidates.size(), done, skipped, outcome);
        notifications.attempt(() -> metrics.recordTick(result));
        notifications.finish();
        return result;
    }
    private void decide(final NpcSnapshot snapshot, final int step, final String trace,
            final NpcNotifications notifications) {
        BehaviorDecision decision;
        try { decision = Objects.requireNonNull(adapter.decide(snapshot,
                new BehaviorContext(actor.zoneId(), step, trace)), "decision"); }
        catch (Exception failure) {
            notifications.attempt(() -> events.publish(event(snapshot, NpcEvent.Type.BEHAVIOR_FAILED, trace)));
            notifications.attempt(() -> metrics.recordBehaviorFailure(actor.zoneId(), snapshot.behavior().type()));
            return;
        }
        if (decision.nextState().equals(snapshot.behavior())) return;
        var changed = new NpcSnapshot(snapshot.npcId(), snapshot.templateId(), snapshot.zoneId(), snapshot.sceneId(),
                snapshot.ownerActorId(), snapshot.lifecycle(), decision.nextState(), snapshot.version() + 1,
                snapshot.spawnedAt(), System.currentTimeMillis(), trace);
        synchronized (stateLock) { npcs.put(snapshot.npcId(), changed); }
        notifications.attempt(() -> events.publish(event(changed, NpcEvent.Type.BEHAVIOR_CHANGED, trace)));
    }
    private NpcEvent event(final NpcSnapshot snapshot, final NpcEvent.Type type, final String trace) {
        return new NpcEvent(actor.zoneId(), snapshot.npcId(), type, snapshot.version(), trace);
    }
    private static ZeroException failure(final NpcErrorCode code) {
        return ZeroException.of(code, code.message(), null);
    }
}
