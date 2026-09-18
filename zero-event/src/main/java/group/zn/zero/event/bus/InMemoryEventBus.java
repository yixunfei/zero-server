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
        List<InterceptorRegistration> interceptorSnapshot;
        List<HandlerRegistration> handlerSnapshot;
        synchronized (this) {
            interceptorSnapshot = List.copyOf(interceptors);
            handlerSnapshot = List.copyOf(handlers.getOrDefault(event.eventType(), List.of()));
        }

        try {
            for (InterceptorRegistration registration : interceptorSnapshot) {
                if (!registration.interceptor().beforePublish(event)) {
                    return CompletableFuture.completedFuture(null);
                }
            }
        } catch (RuntimeException | Error ex) {
            ZeroException zeroException = asZeroException(ex);
            recordDeadLetter(event, zeroException);
            return CompletableFuture.failedFuture(zeroException);
        }

        List<ZeroException> failures = new ArrayList<>();
        CompletionStage<Void> stage = CompletableFuture.completedFuture(null);
        for (HandlerRegistration registration : handlerSnapshot) {
            stage = stage.thenCompose(ignored -> handle(event, registration.handler()).handle((result, failure) -> {
                if (failure != null) {
                    ZeroException current = asZeroException(failure);
                    failures.add(current);
                    recordDeadLetter(event, current);
                }
                return null;
            }));
        }
        return stage.thenCompose(ignored -> {
            if (failures.isEmpty()) {
                return CompletableFuture.completedFuture(null);
            }
            ZeroException first = failures.getFirst();
            for (int index = 1; index < failures.size(); index++) {
                if (failures.get(index) != first) first.addSuppressed(failures.get(index));
            }
            return CompletableFuture.failedFuture(first);
        });
    }

    private CompletionStage<Void> handle(final ZeroEvent event, final EventHandler handler) {
        try {
            CompletionStage<Void> stage = handler.handle(event);
            return Objects.requireNonNull(stage, "handler result");
        } catch (RuntimeException | Error ex) {
            return CompletableFuture.failedFuture(ex);
        }
    }

    private synchronized void unregisterHandler(final HandlerRegistration registration) {
        List<HandlerRegistration> registrations = handlers.get(registration.eventType());
        if (registrations == null) {
            return;
        }
        registrations.remove(registration);
        if (registrations.isEmpty()) {
            handlers.remove(registration.eventType());
        }
    }

    private synchronized void unregisterInterceptor(final InterceptorRegistration registration) {
        interceptors.remove(registration);
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
            ex.addSuppressed(sinkFailure);
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
