package group.zn.zero.framesync;

import group.zn.zero.actor.ActorMessage;
import group.zn.zero.actor.LaneKey;
import group.zn.zero.actor.scheduler.ActorScheduler;
import group.zn.zero.actor.scheduler.ActorSubscription;
import group.zn.zero.actor.scheduler.LocalActorScheduler;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
    private final Map<String, Map<Long, FrameInput>> pending = new HashMap<>();
    private final Map<String, FrameInput> lastInputs = new HashMap<>();
    private final Set<String> seenSequences = new HashSet<>();
    private long frameNo;
    private int buffered;
    private final ActorSubscription inputSubscription;
    private final ActorSubscription tickSubscription;

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
        this.inputSubscription = scheduler.register(SubmitInput.class, (context, message) -> {
            accept(((SubmitInput) message.payload()).input());
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        });
        this.tickSubscription = scheduler.register(AdvanceFrame.class, (context, message) -> {
            advance();
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        });
    }

    public long frameNo() { return frameNo; }

    public CompletionStage<Void> submit(FrameInput input) {
        return scheduler.dispatch(new ActorMessage(lane, new SubmitInput(input)));
    }

    public CompletionStage<Void> tick() {
        return scheduler.dispatch(new ActorMessage(lane, new AdvanceFrame()));
    }

    private void accept(FrameInput input) {
        Objects.requireNonNull(input, "input");
        if (input.payload().length > maxPayloadBytes) throw new IllegalArgumentException("payload exceeds capacity");
        String sequenceKey = input.uid() + "#" + input.inputSeq();
        if (!seenSequences.add(sequenceKey)) return;
        if (input.targetFrame() < frameNo) {
            if (timingPolicy == InputTimingPolicy.REJECT) throw new IllegalArgumentException("late input");
            if (timingPolicy == InputTimingPolicy.MARK) return;
        }
        if (buffered >= maxBufferedInputs) throw new IllegalStateException("input buffer capacity exceeded");
        pending.computeIfAbsent(input.uid(), ignored -> new HashMap<>()).put(input.targetFrame(), input);
        buffered++;
    }

    private void advance() {
        frameNo++;
        List<FrameInput> inputs = new ArrayList<>();
        for (Map.Entry<String, Map<Long, FrameInput>> entry : pending.entrySet()) {
            FrameInput input = entry.getValue().remove(frameNo);
            if (input != null) {
                inputs.add(input);
                buffered--;
                lastInputs.put(entry.getKey(), input);
            } else if (missingPolicy == MissingInputPolicy.REPEAT_LAST && lastInputs.containsKey(entry.getKey())) {
                inputs.add(lastInputs.get(entry.getKey()));
            }
        }
        inputs.sort(Comparator.comparing(FrameInput::uid).thenComparingLong(FrameInput::inputSeq));
        FrameInputBatch batch = new FrameInputBatch(frameNo, inputs);
        simulation.advance(frameNo, batch);
        FrameCommitted committed = new FrameCommitted(matchId, frameNo, batch, inputs.isEmpty() ? "" : inputs.getFirst().traceId());
        events.publish(committed);
        broadcaster.broadcast(committed);
    }

    @Override public void close() { inputSubscription.close(); tickSubscription.close(); }

    public record SubmitInput(FrameInput input) { }
    public record AdvanceFrame() { }
}
