package group.zn.zero.actor.scheduler;

import group.zn.zero.actor.ActorContext;
import group.zn.zero.actor.ActorMessage;
import group.zn.zero.actor.LaneKey;
import group.zn.zero.actor.handler.ActorHandler;
import group.zn.zero.core.error.SystemErrorCode;
import group.zn.zero.core.error.ZeroException;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * 本地确定性 Actor 调度器。
 *
 * <pre>
 * dispatch(message)
 *   -> 根据 laneKey 入队
 *   -> 同一 lane 串行执行
 *   -> 不创建线程池，处理逻辑在调用线程中推进
 * </pre>
 *
 * 本实现用于单进程原型和测试，线程安全。处理器不得在 Actor 热路径中执行不可控远程 IO。
 *
 * @author zn
 */
public final class LocalActorScheduler implements ActorScheduler {

     /**
     * 消息处理器注册表。
     */
    private final Map<Class<?>, ActorHandler> handlers = new LinkedHashMap<>();

    /**
     * lane 队列。
     */
    private final Map<LaneKey, LaneQueue> lanes = new HashMap<>();

    /**
     * 注册 Actor 消息处理器。
     *
     * @param payloadType 消息体类型；不可为空。
     * @param handler 消息处理器；不可为空。
     * @return 注册句柄；调用 close 后取消注册；不可为空；线程安全。
     * @throws ZeroException 当同一消息体类型重复注册时抛出。
     */
    @Override
    public synchronized ActorSubscription register(final Class<?> payloadType, final ActorHandler handler) {
        Objects.requireNonNull(payloadType, "payloadType");
        Objects.requireNonNull(handler, "handler");
        if (handlers.containsKey(payloadType)) {
            throw ZeroException.of(
                    SystemErrorCode.INVALID_ARGUMENT,
                    "Actor handler already registered: " + payloadType.getName(),
                    null);
        }
        handlers.put(payloadType, handler);
        return () -> unregister(payloadType, handler);
    }

    /**
     * 提交 Actor 消息。
     *
     * @param message Actor 消息；不可为空。
     * @return 调度完成信号；不可为空；同一 lane 内顺序完成；线程安全。
     * @throws ZeroException 当消息处理器不存在时抛出，必须绑定 ErrorCode。
     */
    @Override
    public CompletionStage<Void> dispatch(final ActorMessage message) {
        Objects.requireNonNull(message, "message");
        ActorHandler handler;
        Envelope envelope;
        synchronized (this) {
            handler = findHandler(message.payload().getClass());
            envelope = new Envelope(message, handler, new CompletableFuture<>());
            LaneQueue laneQueue = lanes.computeIfAbsent(message.laneKey(), ignored -> new LaneQueue());
            laneQueue.messages().add(envelope);
            if (laneQueue.running()) {
                return envelope.completion();
            }
            laneQueue.running(true);
        }
        drain(message.laneKey());
        return envelope.completion();
    }

    private synchronized void unregister(final Class<?> payloadType, final ActorHandler handler) {
        if (handlers.get(payloadType) == handler) {
            handlers.remove(payloadType);
        }
    }

    private ActorHandler findHandler(final Class<?> payloadType) {
        ActorHandler exact = handlers.get(payloadType);
        if (exact != null) {
            return exact;
        }
        for (Map.Entry<Class<?>, ActorHandler> entry : handlers.entrySet()) {
            if (entry.getKey().isAssignableFrom(payloadType)) {
                return entry.getValue();
            }
        }
        throw ZeroException.of(
                SystemErrorCode.INVALID_ARGUMENT,
                "Actor handler not found: " + payloadType.getName(),
                null);
    }

    private void drain(final LaneKey laneKey) {
        Envelope envelope;
        synchronized (this) {
            LaneQueue laneQueue = lanes.get(laneKey);
            if (laneQueue == null) {
                return;
            }
            envelope = laneQueue.messages().poll();
            if (envelope == null) {
                laneQueue.running(false);
                lanes.remove(laneKey);
                return;
            }
        }
        handle(envelope, laneKey);
    }

    private void handle(final Envelope envelope, final LaneKey laneKey) {
        ActorMessage message = envelope.message();
        try {
            CompletionStage<Void> stage = Objects.requireNonNull(
                    envelope.handler().handle(ActorContext.from(message), message), "actor handler result");
            stage.whenComplete((ignored, ex) -> {
                if (ex == null) {
                    envelope.completion().complete(null);
                } else {
                    envelope.completion().completeExceptionally(asZeroException(ex));
                }
                drain(laneKey);
            });
        } catch (RuntimeException | Error ex) {
            envelope.completion().completeExceptionally(asZeroException(ex));
            drain(laneKey);
        }
    }

    private ZeroException asZeroException(final Throwable throwable) {
        Throwable current = throwable;
        if (current instanceof java.util.concurrent.CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        if (current instanceof ZeroException zeroException) {
            return zeroException;
        }
        return ZeroException.of(
                SystemErrorCode.SYSTEM_ERROR,
                current.getMessage() == null ? SystemErrorCode.SYSTEM_ERROR.message() : current.getMessage(),
                current);
    }

    /**
     * 待处理消息信封。
     *
     * @param message Actor 消息。
     * @param handler Actor 处理器。
     * @param completion 完成信号。
     * @author zn
     */
    private record Envelope(ActorMessage message, ActorHandler handler, CompletableFuture<Void> completion) {
    }

    /**
     * lane 内部队列。
     *
     * @author zn
     */
    private static final class LaneQueue {

        /**
         * 消息队列。
         */
        private final Queue<Envelope> messages = new ArrayDeque<>();

        /**
         * 是否正在执行。
         */
        private boolean running;

        /**
         * 返回消息队列。
         *
         * @return 可变消息队列；可能为空；仅在调度器锁内访问。
         */
        Queue<Envelope> messages() {
            return messages;
        }

        /**
         * 返回 lane 是否正在执行。
         *
         * @return true 表示正在执行；仅在调度器锁内访问。
         */
        boolean running() {
            return running;
        }

        /**
         * 设置 lane 执行状态。
         *
         * @param running true 表示正在执行。
         */
        void running(final boolean running) {
            this.running = running;
        }
    }
}
