package group.zn.zero.room;

import group.zn.zero.actor.ActorMessage;
import group.zn.zero.actor.LaneKey;
import group.zn.zero.actor.scheduler.ActorScheduler;
import group.zn.zero.actor.scheduler.LocalActorScheduler;
import group.zn.zero.actor.handler.ActorHandler;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Local in-memory room service. All mutations are dispatched on the room lane. */
public final class LocalRoomService {
    private final ActorScheduler scheduler;
    private final ConcurrentMap<RoomId, MutableRoom> rooms = new ConcurrentHashMap<>();
    private final Consumer<RoomEvent> eventConsumer;

    public LocalRoomService() { this(new LocalActorScheduler(), ignored -> { }); }
    public LocalRoomService(ActorScheduler scheduler) { this(scheduler, ignored -> { }); }
    public LocalRoomService(ActorScheduler scheduler, Consumer<RoomEvent> eventConsumer) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.eventConsumer = Objects.requireNonNull(eventConsumer, "eventConsumer");
        scheduler.register(Command.class, ActorHandler.sync((context, message) -> apply((Command) message.payload())));
    }
    public RoomSnapshot create(RoomId id, int capacity, long reconnectWindowMillis) {
        Objects.requireNonNull(id, "id");
        if (capacity < 1 || reconnectWindowMillis < 0) throw new IllegalArgumentException("invalid room configuration");
        MutableRoom room = new MutableRoom(id, capacity, reconnectWindowMillis);
        if (rooms.putIfAbsent(id, room) != null) throw failure("room already exists");
        room.emit(RoomEvent.Type.CREATED, null); return room.snapshot();
    }
    public RoomSnapshot snapshot(RoomId id) { return room(id).snapshot(); }
    public RoomStats stats(RoomId id) { return room(id).stats; }
    public List<RoomEvent> events(RoomId id) {
        MutableRoom current = room(id);
        synchronized (current.events) { return List.copyOf(current.events); }
    }
    /** @param id 房间身份；不可为空。 @return 历史淘汰数；线程安全，不修改数据。 */
    public long droppedEventCount(RoomId id) {
        MutableRoom current = room(id);
        synchronized (current.events) { return current.droppedEvents; }
    }
    public CompletionStage<Void> join(RoomId id, String playerId) { return dispatch(id, new Join(player(playerId))); }
    public CompletionStage<Void> leave(RoomId id, String playerId) { return dispatch(id, new Leave(player(playerId))); }
    public CompletionStage<Void> disconnect(RoomId id, String playerId, long now) {
        return dispatch(id, new Disconnect(player(playerId), time(now)));
    }
    public CompletionStage<Void> reconnect(RoomId id, String playerId, long now) { return dispatch(id, new Reconnect(player(playerId), time(now))); }
    public CompletionStage<Void> ready(RoomId id, String playerId) { return dispatch(id, new Ready(player(playerId))); }
    public CompletionStage<Void> start(RoomId id) { return dispatch(id, new Start()); }
    public CompletionStage<Void> close(RoomId id) { return dispatch(id, new Close()); }
    /**
     * 在房间 lane 释放已关闭房间。释放后查询和命令返回不存在。
     * @param id 房间身份；不可为空。
     * @return 完成信号；不可为空；可跨线程调用。
     */
    public CompletionStage<Void> destroy(RoomId id) {
        return dispatch(id, room -> {
            if (room.state != RoomState.CLOSED) throw failure("room must be closed before destroy");
            rooms.remove(id, room);
        });
    }

    public CompletionStage<Settlement> settle(RoomId id, String key, String result) {
        SettlementReply reply = new SettlementReply(nonBlank(key, "settlement key"), Objects.requireNonNull(result, "result"));
        dispatch(id, reply).whenComplete((ignored, failure) -> {
            if (failure != null) {
                reply.resultFuture.completeExceptionally(failure);
            } else {
                reply.resultFuture.complete(reply.value);
            }
        });
        return reply.resultFuture;
    }
    private CompletionStage<Void> dispatch(RoomId id, Operation operation) {
        Objects.requireNonNull(id, "id");
        try { room(id); return scheduler.dispatch(new ActorMessage(
                "room-" + id.value(),
                LaneKey.custom(id.value()),
                "room",
                new Command(id, operation))); } catch (RuntimeException ex) { CompletableFuture<Void> f = new CompletableFuture<>(); f.completeExceptionally(ex); return f; }
    }
    private void apply(Command command) { command.apply(room(command.id)); }
    private MutableRoom room(RoomId id) { MutableRoom room = rooms.get(id); if (room == null) throw failure("room not found: " + id.value()); return room; }
    private static String player(String value) { return nonBlank(value, "player id"); }
    private static String nonBlank(String value, String name) { Objects.requireNonNull(value, name); if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank"); return value; }
    private static long time(long value) { if (value < 0) throw new IllegalArgumentException("time must not be negative"); return value; }
    private static IllegalStateException failure(String message) { return new IllegalStateException(message); }
    private record Command(RoomId id, Operation operation) implements Operation { public void apply(MutableRoom room) { operation.apply(room); } }
    private interface Operation { void apply(MutableRoom room); }
    private record Join(String player) implements Operation { public void apply(MutableRoom r) { r.join(player); } }
    private record Leave(String player) implements Operation { public void apply(MutableRoom r) { r.leave(player); } }
    private record Disconnect(String player, long now) implements Operation { public void apply(MutableRoom r) { r.disconnect(player, now); } }
    private record Reconnect(String player, long now) implements Operation { public void apply(MutableRoom r) { r.reconnect(player, now); } }
    private record Ready(String player) implements Operation { public void apply(MutableRoom r) { r.ready(player); } }
    private record Start() implements Operation { public void apply(MutableRoom r) { r.start(); } }
    private record Close() implements Operation { public void apply(MutableRoom r) { r.close(); } }
    private final class SettlementReply implements Operation {
        final String key;
        final String result;
        final CompletableFuture<Settlement> resultFuture = new CompletableFuture<>();
        Settlement value;
        SettlementReply(String k, String r) { key = k; result = r; }
        public void apply(MutableRoom r) { value = r.settle(key, result); }
    }
    private final class MutableRoom {
        final RoomId id; final int capacity; final long window; final ConcurrentMap<String,RoomMember> members=new ConcurrentHashMap<>(); final ArrayDeque<RoomEvent> events=new ArrayDeque<>(); long droppedEvents; RoomState state=RoomState.WAITING; long seq; String settlementId, settlementResult; RoomStats stats=new RoomStats(0,0,0,0,0,0);
        MutableRoom(RoomId i,int c,long w){id=i;capacity=c;window=w;}
        void join(String p){ if(state==RoomState.CLOSED) throw failure("room closed"); if(members.containsKey(p)&&members.get(p).slot()!=PlayerSlot.LEFT) return; if(state!=RoomState.WAITING) throw failure("room not accepting joins"); if(members.values().stream().filter(m->m.slot()!=PlayerSlot.LEFT).count()>=capacity) throw failure("room full"); members.put(p,new RoomMember(p,PlayerSlot.JOINED,0)); stats=new RoomStats(stats.joins()+1,stats.leaves(),stats.reconnects(),stats.readyChanges(),stats.starts(),stats.settlements()); emit(RoomEvent.Type.JOINED,p); }
        void leave(String p){ if(members.remove(p)==null)return; stats=new RoomStats(stats.joins(),stats.leaves()+1,stats.reconnects(),stats.readyChanges(),stats.starts(),stats.settlements());emit(RoomEvent.Type.LEFT,p); }
        void disconnect(String p,long now){ RoomMember m=member(p); if(m.slot()==PlayerSlot.LEFT)return; members.put(p,new RoomMember(p,PlayerSlot.DISCONNECTED,now)); emit(RoomEvent.Type.DISCONNECTED,p); }
        void reconnect(String p,long now){ RoomMember m=member(p); if(!m.reconnectable(now,window)) throw failure("reconnect window expired"); members.put(p,new RoomMember(p,state==RoomState.RUNNING?PlayerSlot.PLAYING:PlayerSlot.JOINED,0)); stats=new RoomStats(stats.joins(),stats.leaves(),stats.reconnects()+1,stats.readyChanges(),stats.starts(),stats.settlements());emit(RoomEvent.Type.RECONNECTED,p); }
        void ready(String p){ RoomMember m=member(p); if(m.slot()!=PlayerSlot.JOINED) return; members.put(p,new RoomMember(p,PlayerSlot.READY,0));stats=new RoomStats(stats.joins(),stats.leaves(),stats.reconnects(),stats.readyChanges()+1,stats.starts(),stats.settlements());emit(RoomEvent.Type.READY_CHANGED,p); }
        void start(){ if(state!=RoomState.WAITING||members.isEmpty()||members.values().stream().anyMatch(m->m.slot()!=PlayerSlot.READY)) throw failure("room not ready"); state=RoomState.RUNNING; members.replaceAll((p,m)->new RoomMember(p,PlayerSlot.PLAYING,0));stats=new RoomStats(stats.joins(),stats.leaves(),stats.reconnects(),stats.readyChanges(),stats.starts()+1,stats.settlements());emit(RoomEvent.Type.STARTED,null); }
        void close(){ if(state==RoomState.CLOSED)return; state=RoomState.CLOSED; emit(RoomEvent.Type.CLOSED,null); }
        Settlement settle(String k,String result){ Objects.requireNonNull(k);Objects.requireNonNull(result);if(settlementId!=null){if(settlementId.equals(k)&&settlementResult.equals(result))return new Settlement(k,settlementResult);throw failure("settlement already submitted");}if(state!=RoomState.RUNNING)throw failure("room not running");settlementId=k;settlementResult=result;state=RoomState.SETTLED;stats=new RoomStats(stats.joins(),stats.leaves(),stats.reconnects(),stats.readyChanges(),stats.starts(),stats.settlements()+1);emit(RoomEvent.Type.SETTLED,null);return new Settlement(k,result); }
        RoomMember member(String p){RoomMember m=members.get(p);if(m==null)throw failure("member not found");return m;}
        void emit(RoomEvent.Type type, String player) {
            long eventSequence = type == RoomEvent.Type.CREATED ? seq : ++seq;
            RoomEvent event = new RoomEvent(id, eventSequence, type, player, System.currentTimeMillis());
            synchronized (events) {
                if (events.size() == 1024) { events.removeFirst(); droppedEvents++; }
                events.addLast(event);
            }
            try {
                eventConsumer.accept(event);
            } catch (RuntimeException failure) {
                throw group.zn.zero.core.error.ZeroException.of(group.zn.zero.core.error.SystemErrorCode.SYSTEM_ERROR,
                        "room event consumer failed after state transition", failure);
            }
        }
        RoomSnapshot snapshot(){return new RoomSnapshot(id,state,capacity,members.values().stream().sorted(Comparator.comparing(RoomMember::playerId)).toList(),seq,settlementId,settlementResult);}
    }
}
