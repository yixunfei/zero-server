package group.zn.zero.npc;

import group.zn.zero.actor.LaneKey;
import group.zn.zero.actor.scheduler.ActorScheduler;
import java.util.Objects;

/** NPC 区域的受管 lane 身份，不创建线程或执行器。 @author zn */
public final class ZoneActor {
    /** 区域身份。 */
    private final String zoneId;
    /** 所有区域状态修改的调度键。 */
    private final LaneKey laneKey;
    /** 由组合根管理的调度器。 */
    private final ActorScheduler scheduler;
    /** @param zoneId 区域身份。 @param scheduler 受管调度器；不可为空。 */
    public ZoneActor(final String zoneId, final ActorScheduler scheduler) {
        this.zoneId = Objects.requireNonNull(zoneId, "zoneId");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        laneKey = LaneKey.custom("npc-zone:" + zoneId);
    }
    /** @return 区域身份；不可为空，线程安全。 */
    public String zoneId() { return zoneId; }
    /** @return 区域调度键；不可为空，线程安全。 */
    public LaneKey laneKey() { return laneKey; }
    /** @return 受管调度器；不可为空，生命周期由组合根管理。 */
    public ActorScheduler scheduler() { return scheduler; }
}
