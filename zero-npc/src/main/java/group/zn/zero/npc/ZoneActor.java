package group.zn.zero.npc;
import group.zn.zero.actor.LaneKey;
import group.zn.zero.actor.scheduler.LocalActorScheduler;
import java.util.Objects;
public final class ZoneActor { private final String zoneId; private final LaneKey laneKey; private final LocalActorScheduler scheduler; public ZoneActor(String zoneId,LocalActorScheduler scheduler){this.zoneId=Objects.requireNonNull(zoneId);this.scheduler=Objects.requireNonNull(scheduler);this.laneKey=LaneKey.custom("npc-zone:"+zoneId);} public String zoneId(){return zoneId;} public LaneKey laneKey(){return laneKey;} public LocalActorScheduler scheduler(){return scheduler;} }
