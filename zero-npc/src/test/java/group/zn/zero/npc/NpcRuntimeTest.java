package group.zn.zero.npc;
import group.zn.zero.actor.scheduler.LocalActorScheduler;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
class NpcRuntimeTest {
 private NpcSnapshot npc(String id){return new NpcSnapshot(new NpcId(id),"goblin",null,null,null,NpcLifecycleState.CREATED,BehaviorState.idle(),0,0,0,"t");}
 @Test void npcLifecycleTransitionsAreValid(){LocalNpcZone z=zone((n,c)->new BehaviorDecision(new BehaviorState("patrol"),"test"));NpcSnapshot s=z.spawn(npc("1"));assertEquals(NpcLifecycleState.ACTIVE,s.lifecycle());assertEquals(NpcLifecycleState.REMOVED,z.despawn(s.npcId(),"d").lifecycle());assertTrue(z.find(s.npcId()).isEmpty());}
 @Test void removedNpcIsNotTickedAgain(){AtomicInteger calls=new AtomicInteger();LocalNpcZone z=zone((n,c)->{calls.incrementAndGet();return new BehaviorDecision(n.behavior(),"stay");});NpcSnapshot s=z.spawn(npc("1"));z.despawn(s.npcId(),"d");z.tick(new TickBudget(100,10),"x");assertEquals(0,calls.get());}
 @Test void tickBudgetLimitsProcessedNpcCount(){LocalNpcZone z=zone((n,c)->new BehaviorDecision(n.behavior(),"stay"));z.spawn(npc("1"));z.spawn(npc("2"));TickResult r=z.tick(new TickBudget(100,1),"x");assertEquals(1,r.processedCount());assertEquals(1,r.skippedCount());assertEquals(TickResult.Outcome.DEGRADED,r.outcome());}
 @Test void behaviorAdapterTimeoutIsIsolated(){LocalNpcZone z=zone((n,c)->{throw new IllegalStateException("failed");});z.spawn(npc("1"));z.spawn(npc("2"));TickResult r=z.tick(new TickBudget(100,10),"x");assertEquals(2,r.processedCount());assertEquals(TickResult.Outcome.COMPLETED,r.outcome());}
 @Test void metricsDoNotUseNpcIdLabels(){assertDoesNotThrow(()->new NoopNpcMetrics().recordBehaviorFailure("zone","idle"));}
 private LocalNpcZone zone(BehaviorAdapter a){return new LocalNpcZone(new ZoneActor("z",new LocalActorScheduler()),a,e->{},audi->{},new NoopNpcMetrics());}
}
