package group.zn.zero.examples.npctick;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Demonstrates a deterministic, in-memory NPC tick owner lane. */
public final class NpcTickDemo {
    private NpcTickDemo() { }

    /** Runs the local example and prints its stable marker. */
    public static void main(final String[] args) {
        System.out.println(run().marker());
    }

    /** Runs spawn, behavior, bounded tick, and query operations. */
    public static Result run() {
        LocalNpcTickApi api = new LocalNpcTickApi(new Limits(4, 2));
        api.spawn(101L, "zone-1", 10, 20);
        api.setBehavior(101L, "patrol");
        api.tick("zone-1", 1);
        api.tick("zone-1", 2);
        Npc npc = api.query(101L);
        return new Result(api.processedTicks(), api.processedNpcSteps(), npc.behavior(), npc.x(), npc.y());
    }

    /** Fixed local-only limits used by this demonstration. */
    public record Limits(int maxNpcsPerZone, int maxNpcStepsPerTick) {
        public Limits {
            if (maxNpcsPerZone <= 0 || maxNpcStepsPerTick <= 0) {
                throw new IllegalArgumentException("limits must be positive");
            }
        }
    }

    /** Immutable NPC state returned by query. */
    public record Npc(long id, String zoneId, int x, int y, String behavior, long version) { }

    /** Stable summary from the local run. */
    public record Result(int ticks, int steps, String behavior, int x, int y) {
        /** Returns the stable smoke marker; this is not a production claim. */
        public String marker() {
            return "npc-tick=ok|mode=local|ticks=" + ticks + "|steps=" + steps
                    + "|behavior=" + behavior + "|position=" + x + "," + y
                    + "|productionReady=false";
        }
    }

    /** Small owner-lane API; callers must serialize calls on one zone owner. */
    public static final class LocalNpcTickApi {
        private final Limits limits;
        private final Map<Long, Npc> npcs = new LinkedHashMap<>();
        private int processedTicks;
        private int processedNpcSteps;

        public LocalNpcTickApi(final Limits limits) {
            this.limits = Objects.requireNonNull(limits, "limits");
        }

        /** Adds one NPC, rejecting duplicate IDs and bounded capacity overflow. */
        public void spawn(final long id, final String zoneId, final int x, final int y) {
            if (id <= 0 || zoneId == null || zoneId.isBlank()) throw new IllegalArgumentException("invalid NPC");
            if (npcs.containsKey(id) || npcs.size() >= limits.maxNpcsPerZone()) throw new IllegalStateException("NPC capacity or duplicate");
            npcs.put(id, new Npc(id, zoneId, x, y, "idle", 1));
        }

        /** Changes behavior using the local, explicitly supported behavior set. */
        public void setBehavior(final long id, final String behavior) {
            Npc npc = require(id);
            if (!"idle".equals(behavior) && !"patrol".equals(behavior)) throw new IllegalArgumentException("unsupported behavior");
            npcs.put(id, new Npc(id, npc.zoneId(), npc.x(), npc.y(), behavior, npc.version() + 1));
        }

        /** Advances at most the configured number of NPCs; no threads are created. */
        public void tick(final String zoneId, final int tickNo) {
            if (zoneId == null || zoneId.isBlank() || tickNo < 1) throw new IllegalArgumentException("invalid tick");
            int selected = 0;
            for (Npc npc : npcs.values()) {
                if (zoneId.equals(npc.zoneId()) && selected++ < limits.maxNpcStepsPerTick()) {
                    int nextX = "patrol".equals(npc.behavior()) ? npc.x() + 1 : npc.x();
                    npcs.put(npc.id(), new Npc(npc.id(), npc.zoneId(), nextX, npc.y(), npc.behavior(), npc.version() + 1));
                    processedNpcSteps++;
                }
            }
            processedTicks++;
        }

        /** Returns a state snapshot. */
        public Npc query(final long id) { return require(id); }
        /** Returns completed tick count. */
        public int processedTicks() { return processedTicks; }
        /** Returns processed NPC step count. */
        public int processedNpcSteps() { return processedNpcSteps; }
        private Npc require(final long id) { Npc npc = npcs.get(id); if (npc == null) throw new IllegalStateException("NPC not found"); return npc; }
    }
}
