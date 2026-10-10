package group.zn.zero.framesync;

import group.zn.zero.actor.ActorMessage;
import group.zn.zero.actor.LaneKey;
import group.zn.zero.actor.scheduler.ActorScheduler;
import group.zn.zero.actor.scheduler.LocalActorScheduler;
import java.util.ArrayList;
import java.util.HashMap;
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
    private final int maxFutureFrames;
    /** 活跃参与者容量独立于待处理输入容量，只在 match lane 修改。 */
    private final int maxParticipants;
    /** 活跃玩家集合；leave 后释放历史和 last-input。 */
    private final java.util.Set<String> participants = new java.util.TreeSet<>();
    private final Map<String, Map<Long, FrameInput>> pending = new java.util.TreeMap<>();
    private final Map<String, FrameInput> lastInputs = new HashMap<>();
    /** 每位活跃参与者的输入序号水位，仅在对局 lane 修改。 */
    private final Map<String, Long> highestInputSequences = new HashMap<>();
    /** 尚未消费的输入身份不能因近期历史淘汰而失去幂等保护。 */
    private final java.util.Set<InputSequence> pendingSequences = new java.util.HashSet<>();
    /** 跨线程可读帧号；递增仍仅由对局 lane 执行。 */
    private final java.util.concurrent.atomic.AtomicLong frameNo = new java.util.concurrent.atomic.AtomicLong();
    private int buffered;
    /** 模拟状态可能已部分变更；失败后永久隔离本实例，仅允许显式关闭。 */
    private volatile Throwable simulationFailure;
    /** 关闭标志，支持跨线程关闭并保证幂等。 */
    private final java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();
    /** 只保护清理尝试的发布；不在锁内调用 scheduler 或用户回调。 */
    private final Object closeLock = new Object();
    /** 当前清理尝试，成功后保留；异常结束允许下次调用重试。 */
    private java.util.concurrent.CompletableFuture<Void> closeAttempt;

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
        this.maxFutureFrames = config.maxFutureFrames();
        this.maxParticipants = config.maxParticipants();
        this.lane = LaneKey.custom("frame-match:" + matchId);
        FrameDispatchRegistry.register(scheduler);
    }

    public long frameNo() { return frameNo.get(); }

    /** 返回导致对局隔离的原始失败；线程安全，只读，未失败为空。 */
    public java.util.Optional<Throwable> simulationFailure() {
        return java.util.Optional.ofNullable(simulationFailure);
    }

    public CompletionStage<Void> submit(FrameInput input) {
        Objects.requireNonNull(input, "input");
        return dispatch(new FrameDispatchRegistry.Command(() -> {
            requireAvailable();
            accept(input);
        }), input.traceId());
    }

    public CompletionStage<Void> tick() {
        return tick("frame-tick:" + matchId);
    }

    /** 推进一帧并以运行时事件的 traceId 记录提交。 */
    public CompletionStage<Void> tick(final String traceId) {
        String currentTraceId = requireTraceId(traceId);
        return dispatch(new FrameDispatchRegistry.Command(() -> {
            requireAvailable();
            advance(currentTraceId);
        }), currentTraceId);
    }

    /**
     * 玩家离开对局，释放历史与待处理输入；在对局 lane 串行。
     * @param uid 玩家 ID；不可为空。
     * @return 完成信号；线程安全，不重放已离开玩家的最后输入。
     */
    public CompletionStage<Void> leave(final String uid) {
        Objects.requireNonNull(uid, "uid");
        return dispatch(new FrameDispatchRegistry.Command(() -> {
            requireAvailable();
            Map<Long, FrameInput> removed = pending.remove(uid);
            if (removed != null) {
                buffered -= removed.size();
                removed.values().forEach(input ->
                        pendingSequences.remove(new InputSequence(input.uid(), input.inputSeq())));
            }
            participants.remove(uid);
            lastInputs.remove(uid);
            highestInputSequences.remove(uid);
        }), null);
    }

    private CompletionStage<Void> dispatch(final FrameDispatchRegistry.Command command, final String traceId) {
        if (closed.get()) return java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("match closed"));
        if (simulationFailure != null) return java.util.concurrent.CompletableFuture.failedFuture(
                new IllegalStateException("match isolated after simulation failure", simulationFailure));
        return scheduler.dispatch(traceId == null ? new ActorMessage(lane, command) : new ActorMessage(lane, traceId, command));
    }

    private void requireAvailable() {
        if (closed.get()) throw new IllegalStateException("match closed");
        if (simulationFailure != null) throw new IllegalStateException(
                "match isolated after simulation failure", simulationFailure);
    }

    private void accept(FrameInput input) {
        Objects.requireNonNull(input, "input");
        if (input.payloadLength() > maxPayloadBytes) throw new IllegalArgumentException("payload exceeds capacity");
        long currentFrame = frameNo.get();
        if (input.targetFrame() > currentFrame
                && input.targetFrame() - currentFrame > maxFutureFrames) {
            throw new IllegalArgumentException("input is too far ahead");
        }
        Long highest = highestInputSequences.get(input.uid());
        if (highest != null && input.inputSeq() <= highest) return;
        if (input.targetFrame() <= frameNo.get()) {
            if (timingPolicy == InputTimingPolicy.REJECT) throw new IllegalArgumentException("late input");
            if (timingPolicy == InputTimingPolicy.MARK) {
                requireParticipantCapacity(input.uid());
                highestInputSequences.put(input.uid(), input.inputSeq());
                return;
            }
            input = new FrameInput(input.uid(), input.inputSeq(), frameNo.get() + 1,
                    input.clientFrame(), input.payload(), input.traceId());
        }
        Map<Long, FrameInput> playerInputs = pending.get(input.uid());
        requireParticipantCapacity(input.uid());
        boolean replacement = playerInputs != null && playerInputs.containsKey(input.targetFrame());
        if (!replacement && buffered >= maxBufferedInputs) throw new IllegalStateException("input buffer capacity exceeded");
        participants.add(input.uid());
        FrameInput replaced = pending.computeIfAbsent(input.uid(), ignored -> new HashMap<>()).put(input.targetFrame(), input);
        if (replaced != null) pendingSequences.remove(new InputSequence(replaced.uid(), replaced.inputSeq()));
        pendingSequences.add(new InputSequence(input.uid(), input.inputSeq()));
        if (!replacement) buffered++;
        highestInputSequences.put(input.uid(), input.inputSeq());
    }

    private void requireParticipantCapacity(final String uid) {
        // EMPTY/MARK 消费后仍需保存会话水位；不能只计尚有 pending 的玩家。
        if (!highestInputSequences.containsKey(uid) && highestInputSequences.size() >= maxParticipants) {
            throw new IllegalStateException("participant history capacity exceeded");
        }
    }

    /** 结构化身份避免 uid 中分隔符造成歧义。 */
    private record InputSequence(String uid, long sequence) { }

    private void advance(final String tickTraceId) {
        long currentFrame = frameNo.get() + 1;
        List<FrameInput> inputs = new ArrayList<>();
        List<FrameInput> consumed = new ArrayList<>();
        Map<String, FrameInput> nextLastInputs = missingPolicy == MissingInputPolicy.REPEAT_LAST
                ? new HashMap<>(lastInputs) : null;
        for (String uid : participants) {
            FrameInput input = pending.getOrDefault(uid, Map.of()).get(currentFrame);
            if (input != null) {
                inputs.add(input);
                consumed.add(input);
                if (nextLastInputs != null) nextLastInputs.put(uid, input);
            } else if (missingPolicy == MissingInputPolicy.REPEAT_LAST && lastInputs.containsKey(uid)) {
                inputs.add(repeatForFrame(lastInputs.get(uid), currentFrame));
            }
        }
        FrameInputBatch batch = new FrameInputBatch(currentFrame, inputs);
        try {
            simulation.advance(currentFrame, batch);
        } catch (RuntimeException | Error failure) {
            simulationFailure = failure;
            throw failure;
        }

        for (FrameInput input : consumed) {
            Map<Long, FrameInput> playerInputs = pending.get(input.uid());
            if (playerInputs != null && playerInputs.remove(currentFrame) != null) {
                pendingSequences.remove(new InputSequence(input.uid(), input.inputSeq()));
                buffered--;
                if (playerInputs.isEmpty()) {
                    pending.remove(input.uid());
                    if (missingPolicy != MissingInputPolicy.REPEAT_LAST) participants.remove(input.uid());
                }
            }
        }
        frameNo.set(currentFrame);
        lastInputs.clear();
        if (nextLastInputs != null) {
            lastInputs.putAll(nextLastInputs);
        }
        FrameCommitted committed = new FrameCommitted(matchId, currentFrame, batch, tickTraceId);
        events.publish(committed);
        broadcaster.broadcast(committed);
    }

    /**
     * 停止准入并在 match lane 清理。线程安全；在途调用合并，失败后再次调用可重试。
     * 共享 scheduler 的生命周期由调用方管理，必须在清理成功后关闭它。
     * @return 本次尝试的只读完成视图；取消其副本不会取消清理，不得在当前 lane 阻塞等待。
     */
    public CompletionStage<Void> closeAsync() {
        java.util.concurrent.CompletableFuture<Void> attempt;
        synchronized (closeLock) {
            closed.set(true);
            if (closeAttempt != null && !closeAttempt.isCompletedExceptionally()) {
                return closeAttempt.minimalCompletionStage();
            }
            attempt = new java.util.concurrent.CompletableFuture<>();
            closeAttempt = attempt;
        }
        try {
            scheduler.dispatch(new ActorMessage(lane, new FrameDispatchRegistry.Command(this::clearState)))
                    .whenComplete((ignored, failure) -> {
                        if (failure == null) attempt.complete(null);
                        else attempt.completeExceptionally(closeFailure(failure));
                    });
        } catch (RuntimeException | Error failure) {
            attempt.completeExceptionally(closeFailure(failure));
        }
        return attempt.minimalCompletionStage();
    }

    /**
     * 非阻塞关闭便捷入口；仅在清理已完成时正常返回，线程安全。
     * 异步 scheduler 应使用 closeAsync 并组合其完成信号；此方法不在 Actor 上阻塞。
     * @throws group.zn.zero.core.error.ZeroException 清理仍在途，调用方必须观察 closeAsync 的结果。
     * @throws java.util.concurrent.CompletionException 清理失败，原因包含 ErrorCode；允许重试。
     */
    @Override public void close() {
        var completion = closeAsync().toCompletableFuture();
        if (!completion.isDone()) {
            throw group.zn.zero.core.error.ZeroException.of(group.zn.zero.core.error.SystemErrorCode.SYSTEM_ERROR,
                    "match cleanup pending; observe closeAsync() before closing scheduler", null);
        }
        completion.join();
    }

    private static Throwable closeFailure(final Throwable failure) {
        return group.zn.zero.core.error.ZeroException.of(group.zn.zero.core.error.SystemErrorCode.SYSTEM_ERROR,
                "match cleanup failed; retry closeAsync() while scheduler is available", failure);
    }

    private void clearState() {
        participants.clear();
        pending.clear();
        pendingSequences.clear();
        highestInputSequences.clear();
        lastInputs.clear();
        buffered = 0;
    }

    private static FrameInput repeatForFrame(final FrameInput input, final long targetFrame) {
        return new FrameInput(input.uid(), input.inputSeq(), targetFrame, input.clientFrame(), input.payload(), input.traceId());
    }

    private static String requireTraceId(final String traceId) {
        String value = Objects.requireNonNull(traceId, "traceId");
        if (value.isBlank()) throw new IllegalArgumentException("traceId must not be blank");
        return value;
    }

}
