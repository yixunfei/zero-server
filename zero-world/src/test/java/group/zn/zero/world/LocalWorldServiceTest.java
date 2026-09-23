package group.zn.zero.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class LocalWorldServiceTest {
 private LocalWorldService service(){LocalWorldService s=new LocalWorldService();s.createWorld(new World("w",0),List.of(new Shard("a","w",0),new Shard("b","w",0)));return s;}
 @Test void enterWorldAssignsInitialShard(){assertEquals("a",service().enterWorld("e","w").ownerShardId());}
 @Test void moveRequiresOwnerShard(){LocalWorldService s=service();s.enterWorld("e","w");assertThrows(Exception.class,()->s.moveEntity("e","b",1,1));}
 @Test void migrationLocksSourceBeforeHandoff(){LocalWorldService s=service();s.enterWorld("e","w");assertEquals(MigrationState.SOURCE_LOCKED,s.requestMigration("m","e","b").state());assertEquals(EntityStatus.MIGRATING_OUT,s.entityStatus("e"));}
 @Test void targetRejectsStaleRouteEpoch(){LocalWorldService s=service();s.enterWorld("e","w");assertThrows(Exception.class,()->s.requestMigration("m","e","b",99));}
 @Test void migrationIsIdempotentByMigrationId(){LocalWorldService s=service();s.enterWorld("e","w");assertSame(s.requestMigration("m","e","b"),s.requestMigration("m","e","b"));}
 @Test void duplicateMigrationWithDifferentTargetRejected(){LocalWorldService s=service();s.enterWorld("e","w");s.requestMigration("m","e","b");assertThrows(Exception.class,()->s.requestMigration("m","e","a"));}
 @Test void sourceRollbackKeepsEntityWhenPrepareFails(){LocalWorldService s=service();s.enterWorld("e","w");s.requestMigration("m","e","b");s.prepareMigration("m",false);assertEquals("a",s.snapshot("e").ownerShardId());assertEquals(EntityStatus.ACTIVE,s.entityStatus("e"));}
 @Test void targetCommitPublishesNewOwnerOnce(){AtomicInteger n=new AtomicInteger();LocalWorldService s=new LocalWorldService(new group.zn.zero.actor.scheduler.LocalActorScheduler(),e->{if(e.type()==WorldEvent.Type.MIGRATION_COMMITTED)n.incrementAndGet();},WorldMetrics.NOOP);s.createWorld(new World("w",0),List.of(new Shard("a","w",0),new Shard("b","w",0)));s.enterWorld("e","w");s.requestMigration("m","e","b");s.prepareMigration("m");s.commitMigration("m");s.commitMigration("m");assertEquals(1,n.get());}
 @Test void oldMoveRejectedAfterSourceLocked(){LocalWorldService s=service();s.enterWorld("e","w");s.requestMigration("m","e","b");assertThrows(Exception.class,()->s.moveEntity("e","a",1,1));}
 @Test void queryReturnsRouteVersion(){LocalWorldService s=service();s.enterWorld("e","w");assertEquals(0,s.snapshot("e").routeEpoch());}
 @Test void migrationMetricsAvoidHighCardinality(){AtomicInteger n=new AtomicInteger();WorldMetrics m=(op,result,reason)->{assertTrue(op.length()<30);assertTrue(result.length()<30);assertTrue(reason.length()<30);n.incrementAndGet();};LocalWorldService s=new LocalWorldService(new group.zn.zero.actor.scheduler.LocalActorScheduler(),e->{},m);s.createWorld("w","a");s.enterWorld("e","w");s.requestMigration("m","e","a");assertTrue(n.get()>0);}
}
