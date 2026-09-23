package group.zn.zero.framesync;

import group.zn.zero.actor.ActorMessage;
import group.zn.zero.actor.LaneKey;
import group.zn.zero.actor.scheduler.ActorScheduler;
import group.zn.zero.actor.scheduler.LocalActorScheduler;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/**
 * Bounded, single-lane frame runtime. All authoritative state transitions execute on one match lane.
 * Event and broadcast callbacks are notification SPIs; this class creates no executors and performs no IO.
 */
public final class FrameMatchRuntime implements AutoCloseable {
    private final String matchId;
    private final LaneKey lane;
    private final ActorScheduler scheduler;
    private final FrameSimulation simulation;
    private final FrameEventSink events;
    private final FrameBroadcaster broadcaster;
    private final InputTimingPolicy timingPolicy;
    private final MissingInputPolicy missingPolicy;
    private final int maxPayloadBytes;
    private final int maxBufferedInputs;
    private final Map<String, Map<Long, FrameInput>> pending = new java.util.TreeMap<>();
    /** 复用不可变 tick 描述；每次提交仍有独立完成信号。 */
    private final FrameDispatchRegistry.Command tickCommand;
    private final Map<String, FrameInput> lastInputs = new HashMap<>();
    /** 有界的近期已接受输入窗口，仅在对局 lane 修改。 */
    private final LinkedHashSet<InputSequence> seenSequences = new LinkedHashSet<>();
    /** 跨线程可读帧号；递增仍仅由对局 lane 执行。 */
    private final java.util.concurrent.atomic.AtomicLong frameNo = new java.util.concurrent.atomic.AtomicLong();
    private int buffered;
    /** 关闭标志，支持跨线程关闭并保证幂等。 */
    private final java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();

    public FrameMatchRuntime(String matchId, FrameMatchConfig config, FrameSimulation simulation,
                             FrameEventSink events, FrameBroadcaster broadcaster) {
        this(matchId, config, simulation, events, broadcaster, new LocalActorScheduler());
    }

    public FrameMatchRuntime(String matchId, FrameMatchConfig config, FrameSimulation simulation,
                             FrameEventSink events, FrameBroadcaster broadcaster, ActorScheduler scheduler) {
        this.matchId = Objects.requireNonNull(matchId, "matchId");
        Objects.requireNonNull(config, "config");
        this.simulation = Objects.requireNonNull(simulation, "simulation");
        this.events = Objects.requireNonNull(events, "events");
        this.broadcaster = Objects.requireNonNull(broadcaster, "broadcaster");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.timingPolicy = config.inputTimingPolicy();
        this.missingPolicy = config.missingInputPolicy();
        this.maxPayloadBytes = config.maxPayloadBytes();
        this.maxBufferedInputs = config.maxBufferedInputs();
        this.lane = LaneKey.custom("frame-match:" + matchId);
        this.tickCommand = new FrameDispatchRegistry.Command(() -> {
            if (closed.get()) throw new IllegalStateException("match closed");
            advance();
        });
        FrameDispatchRegistry.register(scheduler);
    }

    public long frameNo() { return frameNo.get(); }

    public CompletionStage<Void> submit(FrameInput input) {
        Objects.requireNonNull(input, "input");
        return dispatch(new FrameDispatchRegistry.Command(() -> {
            if (closed.get()) throw new IllegalStateException("match closed");
            accept(input);
        }), input.traceId());
    }

    public CompletionStage<Void> tick() {
        return dispatch(tickCommand, null);
    }

    private CompletionStage<Void> dispatch(final FrameDispatchRegistry.Command command, final String traceId) {
        if (closed.get()) return java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("match closed"));
        return scheduler.dispatch(traceId == null ? new ActorMessage(lane, command) : new ActorMessage(lane, traceId, command));
    }

    private void accept(FrameInput input) {
        Objects.requireNonNull(input, "input");
        if (input.payloadLength() > maxPayloadBytes) throw new IllegalArgumentException("payload exceeds capacity");
        InputSequence sequenceKey = new InputSequence(input.uid(), input.inputSeq());
        if (seenSequences.contains(sequenceKey)) return;
        if (input.targetFrame() <= frameNo.get()) {
            if (timingPolicy == InputTimingPolicy.REJECT) throw new IllegalArgumentException("late input");
            if (timingPolicy == InputTimingPolicy.MARK) {
                remember(sequenceKey);
                return;
            }
            input = new FrameInput(input.uid(), input.inputSeq(), frameNo.get() + 1,
                    input.clientFrame(), input.payload(), input.traceId());
        }
        Map<Long, FrameInput> playerInputs = pending.get(input.uid());
        if (playerInputs == null && pending.size() >= maxBufferedInputs) {
            throw new IllegalStateException("participant history capacity exceeded");
        }
        boolean replacement = playerInputs != null && playerInputs.containsKey(input.targetFrame());
        if (!replacement && buffered >= maxBufferedInputs) throw new IllegalStateException("input buffer capacity exceeded");
        pending.computeIfAbsent(input.uid(), ignored -> new HashMap<>()).put(input.targetFrame(), input);
        if (!replacement) buffered++;
        remember(sequenceKey);
    }

    /** 仅保留有限的近期幂等键，拒绝路径不会占位。 */
    private void remember(InputSequence sequence) {
        seenSequences.add(sequence);
        if (seenSequences.size() > maxBufferedInputs) seenSequences.removeFirst();
    }

    /** 结构化身份避免 uid 中分隔符造成歧义。 */
    private record InputSequence(String uid, long sequence) { }

    private void advance() {
        long currentFrame = frameNo.incrementAndGet();
        List<FrameInput> inputs = new ArrayList<>();
        for (Map.Entry<String, Map<Long, FrameInput>> entry : pending.entrySet()) {
            FrameInput input = entry.getValue().remove(currentFrame);
            if (input != null) {
                inputs.add(input);
                buffered--;
                lastInputs.put(entry.getKey(), input);
            } else if (missingPolicy == MissingInputPolicy.REPEAT_LAST && lastInputs.containsKey(entry.getKey())) {
                inputs.add(lastInputs.get(entry.getKey()));
            }
        }
        if (missingPolicy == MissingInputPolicy.EMPTY) {
            pending.values().removeIf(Map::isEmpty);
            lastInputs.clear();
        }
        FrameInputBatch batch = new FrameInputBatch(currentFrame, inputs);
        simulation.advance(currentFrame, batch);
        FrameCommitted committed = new FrameCommitted(matchId, currentFrame, batch, inputs.isEmpty() ? "" : inputs.getFirst().traceId());
        events.publish(committed);
        broadcaster.broadcast(committed);
    }

    @Override public void close() { closed.set(true); }

}
