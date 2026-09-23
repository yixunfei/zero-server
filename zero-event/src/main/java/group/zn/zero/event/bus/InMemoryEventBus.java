package group.zn.zero.event.bus;

import group.zn.zero.core.error.ErrorCode;
import group.zn.zero.core.error.SystemErrorCode;
import group.zn.zero.core.error.ZeroException;
import group.zn.zero.event.EventType;
import group.zn.zero.event.ZeroEvent;
import group.zn.zero.event.deadletter.DeadLetter;
import group.zn.zero.event.deadletter.DeadLetterSink;
import group.zn.zero.event.handler.EventHandler;
import group.zn.zero.event.interceptor.EventInterceptor;
import java.time.Clock;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 本地内存事件总线。
 *
 * <pre>
 * publish
 *   -> 拦截器按优先级执行
 *   -> 同类型处理器按优先级执行
 *   -> 失败事件写入死信接收器
 * </pre>
 *
 * 本实现不创建线程池，处理器在调用线程中按顺序触发。异步处理器返回的 CompletionStage
 * 会被串联等待，因此同一次 publish 内保持确定性顺序。
 *
 * @author zn
 */
public final class InMemoryEventBus implements EventBus {

    /**
     * 处理器序号生成器。
     */
    private final AtomicLong handlerSequence = new AtomicLong();

    /**
     * 拦截器序号生成器。
     */
    private final AtomicLong interceptorSequence = new AtomicLong();

    /**
     * 事件处理器注册表。
     */
    private final EnumMap<EventType, List<HandlerRegistration>> handlers = new EnumMap<>(EventType.class);

    /**
     * 事件拦截器列表。
     */
    private final List<InterceptorRegistration> interceptors = new ArrayList<>();

    /** 单次 volatile 读取获得同一版本的拦截器与处理器；仅注册时重建。 */
    private volatile RegistrationSnapshot snapshot = new RegistrationSnapshot(new EnumMap<>(EventType.class), List.of());

    /**
     * 死信接收器。
     */
    private final DeadLetterSink deadLetterSink;

    /**
     * 时钟。
     */
    private final Clock clock;

    /**
     * 创建内存事件总线。
     *
     * @param deadLetterSink 死信接收器；不可为空。
     * @throws NullPointerException 当死信接收器为空时抛出。
     */
    public InMemoryEventBus(final DeadLetterSink deadLetterSink) {
        this(deadLetterSink, Clock.systemUTC());
    }

    /**
     * 创建内存事件总线。
     *
     * @param deadLetterSink 死信接收器；不可为空。
     * @param clock 时钟；不可为空。
     * @throws NullPointerException 当死信接收器或时钟为空时抛出。
     */
    public InMemoryEventBus(final DeadLetterSink deadLetterSink, final Clock clock) {
        this.deadLetterSink = Objects.requireNonNull(deadLetterSink, "deadLetterSink");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 按优先级注册事件处理器。
     *
     * @param eventType 事件类型；不可为空。
     * @param handler 事件处理器；不可为空。
     * @param priority 处理器优先级，数值越小越早执行。
     * @return 订阅句柄；调用 close 后取消注册；不可为空；线程安全。
     */
    @Override
    public synchronized EventSubscription register(
            final EventType eventType,
            final EventHandler handler,
            final int priority) {
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(handler, "handler");

        HandlerRegistration registration = new HandlerRegistration(
                eventType,
                handler,
                priority,
                handlerSequence.getAndIncrement());
        handlers.computeIfAbsent(eventType, ignored -> new ArrayList<>()).add(registration);
        handlers.get(eventType).sort(HandlerRegistration::compareTo);
        rebuildSnapshot();

        return () -> unregisterHandler(registration);
    }

    /**
     * 按优先级注册事件拦截器。
     *
     * @param interceptor 事件拦截器；不可为空。
     * @param priority 拦截器优先级，数值越小越早执行。
     * @return 订阅句柄；调用 close 后取消注册；不可为空；线程安全。
     */
    @Override
    public synchronized EventSubscription addInterceptor(final EventInterceptor interceptor, final int priority) {
        Objects.requireNonNull(interceptor, "interceptor");

        InterceptorRegistration registration = new InterceptorRegistration(
                interceptor,
                priority,
                interceptorSequence.getAndIncrement());
        interceptors.add(registration);
        interceptors.sort(InterceptorRegistration::compareTo);
        rebuildSnapshot();

        return () -> unregisterInterceptor(registration);
    }

    /**
     * 发布事件。
     *
     * @param event 事件对象；不可为空。
     * @return 发布完成信号；不可为空；调用线程内按注册顺序执行。
     * @throws ZeroException 发布失败时抛出，必须绑定 ErrorCode。
     */
    @Override
    public CompletionStage<Void> publish(final ZeroEvent event) {
        Objects.requireNonNull(event, "event");
        RegistrationSnapshot registrations = snapshot;
        List<HandlerRegistration> handlerSnapshot = registrations.handlers().getOrDefault(event.eventType(), List.of());

        try {
            for (InterceptorRegistration registration : registrations.interceptors()) {
                if (!registration.interceptor().beforePublish(event)) {
                    return CompletableFuture.completedFuture(null);
                }
            }
        } catch (RuntimeException | Error ex) {
            ZeroException zeroException = asZeroException(ex);
            recordDeadLetter(event, zeroException);
            return CompletableFuture.failedFuture(zeroException);
        }

        if (handlerSnapshot.isEmpty()) return CompletableFuture.completedFuture(null);
        return dispatch(event, handlerSnapshot, 0, null, null);
    }

    private CompletionStage<Void> dispatch(final ZeroEvent event, final List<HandlerRegistration> registrations,
            final int start, final List<ZeroException> priorFailures, final CompletableFuture<Void> completion) {
        List<ZeroException> failures = priorFailures;
        for (int index = start; index < registrations.size(); index++) {
            CompletionStage<Void> stage;
            try {
                stage = Objects.requireNonNull(registrations.get(index).handler().handle(event), "handler result");
                // 仅优化标准 CompletableFuture；不调用通用 stage 的 toCompletableFuture 或阻塞未完成结果。
                if (stage.getClass() == CompletableFuture.class && ((CompletableFuture<?>) stage).isDone()) {
                    ((CompletableFuture<?>) stage).join();
                    continue;
                }
            } catch (RuntimeException | Error failure) {
                failures = appendFailure(event, failures, failure);
                continue;
            }
            Continuation continuation = new Continuation(event, registrations, index + 1, failures,
                    completion == null ? new CompletableFuture<>() : completion);
            stage.whenComplete(continuation::completed);
            if (continuation.handoff.compareAndSet(0, 1)) return continuation.completion;
            // 注册回调期间已完成：原调用栈迭代处理，防止同步 CompletionStage 引起深层递归。
            failures = continuation.failuresAfterCompletion();
        }
        return finish(failures, completion);
    }

    private List<ZeroException> appendFailure(final ZeroEvent event, final List<ZeroException> failures,
            final Throwable failure) {
        List<ZeroException> result = failures == null ? new ArrayList<>() : failures;
        ZeroException current = asZeroException(failure);
        result.add(current);
        recordDeadLetter(event, current);
        return result;
    }

    private CompletionStage<Void> finish(final List<ZeroException> failures, final CompletableFuture<Void> completion) {
        CompletableFuture<Void> result = completion == null ? new CompletableFuture<>() : completion;
        if (failures == null) {
            result.complete(null);
        } else {
            ZeroException first = failures.getFirst();
            for (int index = 1; index < failures.size(); index++) {
                if (failures.get(index) != first) first.addSuppressed(failures.get(index));
            }
            result.completeExceptionally(first);
        }
        return result;
    }

    /** 只为无法同步读取的完成信号保存续调；handoff 发布跨线程结果且避免递归。 @author zn */
    private final class Continuation {
        /** 0=注册中，1=已交接，2=已完成。 */
        private final AtomicInteger handoff = new AtomicInteger();
        /** 当前事件。 */
        private final ZeroEvent event;
        /** 本次派发的固定注册快照。 */
        private final List<HandlerRegistration> registrations;
        /** 下一处理器。 */
        private final int next;
        /** 已有失败；只由当前执行者访问。 */
        private final List<ZeroException> failures;
        /** 单次发布私有的完成信号。 */
        private final CompletableFuture<Void> completion;
        /** 完成失败，由 handoff 的 release/acquire 发布。 */
        private Throwable failure;

        private Continuation(final ZeroEvent event, final List<HandlerRegistration> registrations, final int next,
                final List<ZeroException> failures, final CompletableFuture<Void> completion) {
            this.event = event;
            this.registrations = registrations;
            this.next = next;
            this.failures = failures;
            this.completion = completion;
        }

        private void completed(final Void ignored, final Throwable exception) {
            failure = exception;
            if (handoff.getAndSet(2) == 1) dispatch(event, registrations, next, failuresAfterCompletion(), completion);
        }

        private List<ZeroException> failuresAfterCompletion() {
            return failure == null ? failures : appendFailure(event, failures, failure);
        }
    }

    private void rebuildSnapshot() {
        EnumMap<EventType, List<HandlerRegistration>> next = new EnumMap<>(EventType.class);
        handlers.forEach((type, registrations) -> next.put(type, List.copyOf(registrations)));
        snapshot = new RegistrationSnapshot(next, List.copyOf(interceptors));
    }

    /** 发布后不再变更；注册锁内构造，以 volatile 原子替换。 @author zn */
    private record RegistrationSnapshot(EnumMap<EventType, List<HandlerRegistration>> handlers,
            List<InterceptorRegistration> interceptors) { }

    private synchronized void unregisterHandler(final HandlerRegistration registration) {
        List<HandlerRegistration> registrations = handlers.get(registration.eventType());
        if (registrations == null) {
            return;
        }
        if (!registrations.remove(registration)) return;
        if (registrations.isEmpty()) {
            handlers.remove(registration.eventType());
        }
        rebuildSnapshot();
    }

    private synchronized void unregisterInterceptor(final InterceptorRegistration registration) {
        if (interceptors.remove(registration)) rebuildSnapshot();
    }

    private void recordDeadLetter(final ZeroEvent event, final ZeroException ex) {
        try {
            deadLetterSink.record(new DeadLetter(
                    event,
                    ex.errorCode(),
                    ex.message(),
                    0,
                    clock.instant()));
        } catch (RuntimeException | Error sinkFailure) {
            if (sinkFailure != ex) ex.addSuppressed(sinkFailure);
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
        ErrorCode errorCode = SystemErrorCode.SYSTEM_ERROR;
        return ZeroException.of(errorCode, current.getMessage() == null ? errorCode.message() : current.getMessage(), current);
    }

    /**
     * 事件处理器注册信息。
     *
     * @param eventType 事件类型。
     * @param handler 事件处理器。
     * @param priority 优先级。
     * @param sequence 注册序号。
     * @author zn
     */
    private record HandlerRegistration(EventType eventType, EventHandler handler, int priority, long sequence)
            implements Comparable<HandlerRegistration> {

        /**
         * 比较注册优先级。
         *
         * @param other 另一注册信息；不可为空。
         * @return 负数表示当前对象先执行；线程安全。
         */
        @Override
        public int compareTo(final HandlerRegistration other) {
            int priorityCompare = Integer.compare(priority, other.priority);
            return priorityCompare != 0 ? priorityCompare : Long.compare(sequence, other.sequence);
        }
    }

    /**
     * 事件拦截器注册信息。
     *
     * @param interceptor 事件拦截器。
     * @param priority 优先级。
     * @param sequence 注册序号。
     * @author zn
     */
    private record InterceptorRegistration(EventInterceptor interceptor, int priority, long sequence)
            implements Comparable<InterceptorRegistration> {

        /**
         * 比较注册优先级。
         *
         * @param other 另一注册信息；不可为空。
         * @return 负数表示当前对象先执行；线程安全。
         */
        @Override
        public int compareTo(final InterceptorRegistration other) {
            int priorityCompare = Integer.compare(priority, other.priority);
            return priorityCompare != 0 ? priorityCompare : Long.compare(sequence, other.sequence);
        }
    }
}
