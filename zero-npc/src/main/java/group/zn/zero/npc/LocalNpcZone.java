package group.zn.zero.npc;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
public final class LocalNpcZone { private final ZoneActor actor; private final Map<NpcId,NpcSnapshot> npcs=new LinkedHashMap<>(); private final BehaviorAdapter adapter; private final NpcEventSink events; private final NpcAuditSink audit; private final NpcMetrics metrics;
 public LocalNpcZone(ZoneActor a,BehaviorAdapter b,NpcEventSink e,NpcAuditSink au,NpcMetrics m){actor=Objects.requireNonNull(a);adapter=Objects.requireNonNull(b);events=Objects.requireNonNull(e);audit=Objects.requireNonNull(au);metrics=Objects.requireNonNull(m);}
 public synchronized NpcSnapshot spawn(NpcSnapshot n){if(n.lifecycle()!=NpcLifecycleState.CREATED)throw new IllegalArgumentException("NPC must be CREATED");if(npcs.containsKey(n.npcId()))throw new IllegalStateException("NPC already exists: "+n.npcId());NpcSnapshot x=new NpcSnapshot(n.npcId(),n.templateId(),actor.zoneId(),n.sceneId(),actor.zoneId(),NpcLifecycleState.ACTIVE,n.behavior(),n.version()+1,n.spawnedAt(),n.lastTickAt(),n.traceId());npcs.put(x.npcId(),x);events.publish(new NpcEvent(actor.zoneId(),x.npcId(),NpcEvent.Type.SPAWNED,x.version(),x.traceId()));audit.record(new NpcAuditEvent("spawn",actor.zoneId(),x.npcId(),x.traceId()));metrics.recordSpawn();return x;}
 public synchronized NpcSnapshot despawn(NpcId id,String trace){NpcSnapshot n=Objects.requireNonNull(npcs.remove(id));NpcSnapshot x=new NpcSnapshot(n.npcId(),n.templateId(),n.zoneId(),n.sceneId(),n.ownerActorId(),NpcLifecycleState.REMOVED,n.behavior(),n.version()+1,n.spawnedAt(),n.lastTickAt(),trace);events.publish(new NpcEvent(actor.zoneId(),id,NpcEvent.Type.DESPAWNED,x.version(),trace));audit.record(new NpcAuditEvent("despawn",actor.zoneId(),id,trace));metrics.recordDespawn();return x;}
 /**
  * 按 owner 串行 tick；线程不安全，回调移除或替换的 NPC 不能执行旧快照。
  * @param budget 时间和操作上限；任一为零则全部跳过，不调用行为 Adapter。
  * @param trace 链路标识。
  * @return 不可变的处理、跳过及降级结果；记录对应指标。
  */
 public synchronized TickResult tick(TickBudget budget, String trace) {
     int candidates = npcs.size(), done = 0, skip = 0;
     if (budget.budgetOps() == 0 || budget.budgetMillis() == 0) {
         return finishTick(candidates, 0, candidates);
     }
     long start = System.nanoTime();
     for (NpcSnapshot snapshot : new ArrayList<>(npcs.values())) {
         if (done >= budget.budgetOps()
                 || (System.nanoTime() - start) / 1_000_000 >= budget.budgetMillis()) {
             skip = candidates - done;
             break;
         }
         if (npcs.get(snapshot.npcId()) != snapshot) {
             skip++;
             continue;
         }
         try {
             BehaviorDecision decision = adapter.decide(snapshot, new BehaviorContext(actor.zoneId(), done + 1, trace));
             if (!decision.nextState().equals(snapshot.behavior()) && npcs.get(snapshot.npcId()) == snapshot) {
                 npcs.put(snapshot.npcId(), new NpcSnapshot(snapshot.npcId(), snapshot.templateId(), snapshot.zoneId(),
                         snapshot.sceneId(), snapshot.ownerActorId(), snapshot.lifecycle(), decision.nextState(),
                         snapshot.version() + 1, snapshot.spawnedAt(), System.currentTimeMillis(), trace));
                 events.publish(new NpcEvent(actor.zoneId(), snapshot.npcId(), NpcEvent.Type.BEHAVIOR_CHANGED,
                         snapshot.version() + 1, trace));
             }
             done++;
         } catch (Exception failure) {
             events.publish(new NpcEvent(actor.zoneId(), snapshot.npcId(), NpcEvent.Type.BEHAVIOR_FAILED,
                     snapshot.version(), trace));
             metrics.recordBehaviorFailure(actor.zoneId(), snapshot.behavior().type());
             done++;
         }
     }
     return finishTick(candidates, done, skip);
 }
 private TickResult finishTick(int candidates, int done, int skip) {
     TickResult.Outcome outcome = skip == 0 ? TickResult.Outcome.COMPLETED
             : (done == 0 ? TickResult.Outcome.SKIPPED : TickResult.Outcome.DEGRADED);
     TickResult result = new TickResult(actor.zoneId(), candidates, done, skip, outcome);
     metrics.recordTick(result);
     return result;
 }
 public synchronized Optional<NpcSnapshot> find(NpcId id){return Optional.ofNullable(npcs.get(id));} public synchronized int size(){return npcs.size();}
}
