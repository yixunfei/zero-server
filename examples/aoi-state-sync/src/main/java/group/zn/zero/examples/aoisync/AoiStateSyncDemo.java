package group.zn.zero.examples.aoisync;

import java.util.*;

/** A deliberately small, deterministic fake local AOI/state-sync API. */
public final class AoiStateSyncDemo {
    public static void main(String[] args) {
        Demo demo = new Demo();
        demo.run();
    }

    static final class Demo {
        final LocalAoiApi api = new LocalAoiApi(new Limits(32, 8, 2, 256));
        void run() {
            api.addObserver("observer-1", new Point(0, 0), 5);
            api.addEntity("player-1", new Point(2, 1), "hero");
            api.addEntity("npc-1", new Point(20, 20), "npc");
            api.moveEntity("player-1", new Point(3, 1));
            List<Entity> visible = api.queryVisible("observer-1");
            Snapshot snapshot = api.snapshot("observer-1");
            api.moveEntity("player-1", new Point(30, 30));
            DeltaResult applied = api.applyDelta(new Delta(snapshot.version(), snapshot.version() + 1, "player-1", "left"));
            DeltaResult stale = api.applyDelta(new Delta(999, 1000, "player-1", "bad-baseline"));
            api.addObserver("small-queue", new Point(0, 0), 100);
            api.addEntity("queue-1", new Point(1, 1), "npc");
            api.addEntity("queue-2", new Point(1, 2), "npc");
            System.out.println("aoi-state-sync=ok|mode=local|sceneSeq=" + api.sceneSeq()
                    + "|syncSeq=" + api.syncSeq("observer-1") + "|visible=" + visible.size()
                    + "|snapshot=" + snapshot.entities().size() + "|deltaApplied=" + applied.applied()
                    + "|resyncRequired=" + stale.resyncRequired() + "|queueRejected=" + api.queueRejected()
                    + "|productionReady=false");
        }
    }

    public record Point(int x, int y) { }
    public record Entity(String id, Point position, String type, long stateVersion) { }
    public record Limits(int maxEntities, int maxVisible, int maxObserverQueue, int maxPayloadBytes) { }
    public record Snapshot(long version, List<Entity> entities) {
        public Snapshot { entities = List.copyOf(entities); }
    }
    public record Delta(long baselineVersion, long targetVersion, String entityId, String payload) { }
    public record DeltaResult(boolean applied, boolean resyncRequired) { }
    public record Event(long syncSeq, String type, String entityId) { }

    /** In-memory owner-lane API: callers must serialize mutation calls on the scene owner. */
    public static final class LocalAoiApi {
        private final Limits limits;
        private final Map<String, Entity> entities = new LinkedHashMap<>();
        private final Map<String, Observer> observers = new LinkedHashMap<>();
        private long sceneSeq;
        private long queueRejected;
        LocalAoiApi(Limits limits) { this.limits = Objects.requireNonNull(limits); }

        public void addObserver(String id, Point position, int viewRange) {
            requireText(id); Objects.requireNonNull(position);
            if (viewRange < 0) throw new IllegalArgumentException("viewRange must be non-negative");
            observers.put(id, new Observer(id, position, viewRange, limits.maxObserverQueue()));
        }
        public void addEntity(String id, Point position, String type) {
            requireText(id); Objects.requireNonNull(position); requireText(type);
            if (entities.size() >= limits.maxEntities() || entities.containsKey(id)) throw new IllegalStateException("entity capacity or duplicate");
            if (payloadSize(type) > limits.maxPayloadBytes()) throw new IllegalArgumentException("payload too large");
            entities.put(id, new Entity(id, position, type, ++sceneSeq));
            emitVisibility();
        }
        public void moveEntity(String id, Point position) {
            Entity old = entity(id); Objects.requireNonNull(position);
            entities.put(id, new Entity(id, position, old.type(), old.stateVersion() + 1));
            sceneSeq++; emitVisibility();
        }
        public List<Entity> queryVisible(String observerId) {
            Observer observer = observer(observerId);
            return entities.values().stream().filter(e -> visible(observer, e)).limit(limits.maxVisible()).toList();
        }
        public Snapshot snapshot(String observerId) { return new Snapshot(sceneSeq, queryVisible(observerId)); }
        public DeltaResult applyDelta(Delta delta) {
            Objects.requireNonNull(delta);
            boolean known = delta.baselineVersion() >= 0 && delta.baselineVersion() <= sceneSeq;
            return known ? new DeltaResult(true, false) : new DeltaResult(false, true);
        }
        public List<Event> events(String observerId) { return List.copyOf(observer(observerId).events); }
        public long sceneSeq() { return sceneSeq; }
        public long syncSeq(String observerId) { return observer(observerId).syncSeq; }
        public long queueRejected() { return queueRejected; }
        private void emitVisibility() {
            for (Observer observer : observers.values()) {
                Set<String> now = new LinkedHashSet<>();
                for (Entity entity : queryVisible(observer.id)) now.add(entity.id());
                for (String id : now) if (!observer.visible.remove(id)) emit(observer, "appeared", id);
                for (String id : new ArrayList<>(observer.visible)) if (!now.contains(id)) { observer.visible.remove(id); emit(observer, "disappeared", id); }
                observer.visible.addAll(now);
            }
        }
        private void emit(Observer observer, String type, String id) {
            if (observer.events.size() >= observer.capacity) { queueRejected++; return; }
            observer.events.add(new Event(++observer.syncSeq, type, id));
        }
        private boolean visible(Observer o, Entity e) { return Math.max(Math.abs(o.position.x() - e.position().x()), Math.abs(o.position.y() - e.position().y())) <= o.viewRange; }
        private Entity entity(String id) { requireText(id); Entity e = entities.get(id); if (e == null) throw new NoSuchElementException(id); return e; }
        private Observer observer(String id) { requireText(id); Observer o = observers.get(id); if (o == null) throw new NoSuchElementException(id); return o; }
        private static int payloadSize(String s) { return s.getBytes(java.nio.charset.StandardCharsets.UTF_8).length; }
        private static void requireText(String value) { if (value == null || value.isBlank()) throw new IllegalArgumentException("blank id/value"); }
        private static final class Observer {
            final String id; final Point position; final int viewRange, capacity; long syncSeq; final List<Event> events = new ArrayList<>(); final Set<String> visible = new LinkedHashSet<>();
            Observer(String id, Point position, int viewRange, int capacity) { this.id=id; this.position=position; this.viewRange=viewRange; this.capacity=capacity; }
        }
    }
}
