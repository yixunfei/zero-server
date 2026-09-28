package group.zn.zero.room;

import group.zn.zero.actor.ActorMessage;
import group.zn.zero.actor.LaneKey;
import group.zn.zero.actor.scheduler.ActorScheduler;
import group.zn.zero.actor.scheduler.LocalActorScheduler;
import group.zn.zero.actor.handler.ActorHandler;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Local in-memory room service. All mutations are dispatched on the room lane. */
public final class LocalRoomService {
    /** 默认保留的内存事件历史条数。 */
    public static final int DEFAULT_EVENT_HISTORY_CAPACITY = 1024;
    private final ActorScheduler scheduler;
    private final ConcurrentMap<RoomId, MutableRoom> rooms = new ConcurrentHashMap<>();
    private final Consumer<RoomEvent> eventConsumer;
    /** 每个房间内存历史容量；不限制实时事件交付。 */
    private final int eventHistoryCapacity;

    public LocalRoomService() { this(new LocalActorScheduler(), ignored -> { }, DEFAULT_EVENT_HISTORY_CAPACITY); }
    public LocalRoomService(ActorScheduler scheduler) { this(scheduler, ignored -> { }, DEFAULT_EVENT_HISTORY_CAPACITY); }
    public LocalRoomService(ActorScheduler scheduler, Consumer<RoomEvent> eventConsumer) {
        this(scheduler, eventConsumer, DEFAULT_EVENT_HISTORY_CAPACITY);
    }
    /**
     * 创建房间服务并配置内存事件历史容量。
     *
     * <p>事件消费者仍会收到每一条实时事件；容量只限制 {@link #events(RoomId)} 的内存历史。
     * 需要可靠补洞时应在消费者中接入持久事件日志。
     *
     * @param scheduler actor 调度器；不可为空。
     * @param eventConsumer 实时事件消费者；不可为空。
     * @param eventHistoryCapacity 每个房间保留的历史条数；必须大于 0。
     */
    public LocalRoomService(ActorScheduler scheduler, Consumer<RoomEvent> eventConsumer,
            int eventHistoryCapacity) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.eventConsumer = Objects.requireNonNull(eventConsumer, "eventConsumer");
        if (eventHistoryCapacity <= 0) {
            throw new IllegalArgumentException("eventHistoryCapacity must be positive");
        }
        this.eventHistoryCapacity = eventHistoryCapacity;
        scheduler.register(Command.class, ActorHandler.sync((context, message) -> apply((Command) message.payload())));
    }
    public RoomSnapshot create(RoomId id, int capacity, long reconnectWindowMillis) {
        Objects.requireNonNull(id, "id");
        if (capacity < 1 || reconnectWindowMillis < 0) throw new IllegalArgumentException("invalid room configuration");
        MutableRoom room = new MutableRoom(id, capacity, reconnectWindowMillis, eventHistoryCapacity);
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
    /**
     * 原子读取保留窗口和缺口标志，线程安全；不修改房间状态。
     * @param id 房间身份；不可为空。
     * @param afterSequence 已消费的序号，-1 表示从创建事件开始。
     * @return 不可变历史及缺口元数据；缺口不能靠本地历史补齐。
     * @throws IllegalArgumentException 序号小于 -1。
     */
    public RoomEventHistory eventHistory(RoomId id, long afterSequence) {
        if (afterSequence < -1) throw new IllegalArgumentException("afterSequence must be >= -1");
        MutableRoom current = room(id);
        synchronized (current.events) {
            long first = current.events.isEmpty() ? -1 : current.events.getFirst().sequence();
            long latest = current.events.isEmpty() ? -1 : current.events.getLast().sequence();
            return new RoomEventHistory(current.events.stream()
                    .filter(event -> event.sequence() > afterSequence).toList(), first, latest,
                    current.droppedEvents, current.droppedEvents > 0 && afterSequence < first - 1);
        }
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
        try { MutableRoom expected = room(id); return scheduler.dispatch(new ActorMessage(
                "room-" + id.value(),
                LaneKey.custom(id.value()),
                "room",
                new Command(id, expected, operation))); } catch (RuntimeException ex) { CompletableFuture<Void> f = new CompletableFuture<>(); f.completeExceptionally(ex); return f; }
    }
    private void apply(Command command) {
        MutableRoom current = room(command.id);
        if (current != command.expected) throw failure("room generation has changed");
        RoomCheckpoint checkpoint = current.checkpoint();
        try {
            command.apply(current);
        } catch (RoomEventDeliveryFailure failure) {
            // 事件已经进入历史队列并交给消费者，不能再回滚已提交的房间状态。
            throw failure;
        } catch (RuntimeException failure) {
            current.restore(checkpoint);
            throw failure;
        }
    }
    private MutableRoom room(RoomId id) { MutableRoom room = rooms.get(id); if (room == null) throw failure("room not found: " + id.value()); return room; }
    private static String player(String value) { return nonBlank(value, "player id"); }
    private static String nonBlank(String value, String name) { Objects.requireNonNull(value, name); if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank"); return value; }
    private static long time(long value) { if (value < 0) throw new IllegalArgumentException("time must not be negative"); return value; }
    private static IllegalStateException failure(String message) { return new IllegalStateException(message); }
    private record Command(RoomId id, MutableRoom expected, Operation operation) implements Operation { public void apply(MutableRoom room) { operation.apply(room); } }
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
        final RoomId id; final int capacity; final long window; final int historyCapacity; final ConcurrentMap<String,RoomMember> members=new ConcurrentHashMap<>(); final ArrayDeque<RoomEvent> events=new ArrayDeque<>(); long droppedEvents; RoomState state=RoomState.WAITING; long seq; String settlementId, settlementResult; RoomStats stats=new RoomStats(0,0,0,0,0,0);
        MutableRoom(RoomId i,int c,long w,int h){id=i;capacity=c;window=w;historyCapacity=h;}
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
                if (events.size() == historyCapacity) { events.removeFirst(); droppedEvents++; }
                events.addLast(event);
            }
            try {
                eventConsumer.accept(event);
            } catch (RuntimeException failure) {
                throw new RoomEventDeliveryFailure(failure);
            }
        }
        RoomCheckpoint checkpoint() {
            synchronized (events) {
                return new RoomCheckpoint(state, new HashMap<>(members), new ArrayDeque<>(events),
                        droppedEvents, seq, settlementId, settlementResult, stats);
            }
        }
        void restore(RoomCheckpoint checkpoint) {
            members.clear();
            members.putAll(checkpoint.members());
            synchronized (events) {
                events.clear();
                events.addAll(checkpoint.events());
            }
            state = checkpoint.state();
            droppedEvents = checkpoint.droppedEvents();
            seq = checkpoint.seq();
            settlementId = checkpoint.settlementId();
            settlementResult = checkpoint.settlementResult();
            stats = checkpoint.stats();
        }
        RoomSnapshot snapshot(){return new RoomSnapshot(id,state,capacity,members.values().stream().sorted(Comparator.comparing(RoomMember::playerId)).toList(),seq,settlementId,settlementResult);}
    }
    private record RoomCheckpoint(
            RoomState state,
            Map<String, RoomMember> members,
            ArrayDeque<RoomEvent> events,
            long droppedEvents,
            long seq,
            String settlementId,
            String settlementResult,
            RoomStats stats) { }

    /** 标识事件已交付后失败，apply 不得回滚已提交状态。 */
    private static final class RoomEventDeliveryFailure extends group.zn.zero.core.error.ZeroException {
        RoomEventDeliveryFailure(final RuntimeException cause) {
            super(group.zn.zero.core.error.SystemErrorCode.SYSTEM_ERROR,
                    "room event consumer failed after state transition", cause);
        }
    }
}
