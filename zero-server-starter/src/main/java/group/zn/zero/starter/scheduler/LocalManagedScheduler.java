package group.zn.zero.starter.scheduler;

import group.zn.zero.core.error.ErrorCode;
import group.zn.zero.core.error.SystemErrorCode;
import group.zn.zero.core.error.ZeroException;
import group.zn.zero.core.lifecycle.AbstractLifecycle;
import group.zn.zero.core.scheduler.ManagedScheduler;
import group.zn.zero.core.scheduler.ScheduleType;
import group.zn.zero.core.scheduler.ScheduledTask;
import group.zn.zero.core.scheduler.ScheduledTaskContext;
import group.zn.zero.core.scheduler.ScheduledTaskDefinition;
import group.zn.zero.core.scheduler.ScheduledTaskEvent;
import group.zn.zero.core.scheduler.ScheduledTaskEventType;
import group.zn.zero.core.scheduler.ScheduledTaskExecutionTiming;
import group.zn.zero.core.scheduler.ScheduledTaskFailurePolicy;
import group.zn.zero.core.scheduler.ScheduledTaskHandle;
import group.zn.zero.core.scheduler.ScheduledTaskObserver;
import group.zn.zero.core.scheduler.ScheduledTaskSnapshot;
import group.zn.zero.core.scheduler.ScheduledTaskState;
import group.zn.zero.core.scheduler.SchedulerErrorCode;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Starter 本地单进程受管定时任务实现。
 *
 * <pre>
 * one-shot timer
 *   -> 状态与容量判断
 *   -> 提交受管 background executor
 *   -> 调用 ScheduledTask 并跟踪 CompletionStage
 *   -> 完成后释放预算并按策略重排
 * </pre>
 *
 * <p>本实现只拥有单个 timer resource，不创建业务执行器。用户任务、observer、日志和指标均不会在
 * timer thread 中运行。fixed-rate 仅使用一次性 timer 自重排，错过节拍不排队、不补跑、不追赶。
 *
 * @author zn
 */
public final class LocalManagedScheduler extends AbstractLifecycle implements ManagedScheduler {

    /**
     * fallback 系统日志。
     */
    private static final System.Logger FALLBACK_LOGGER = System.getLogger(LocalManagedScheduler.class.getName());

    /**
     * 业务与 observer 使用的受管非内联执行器。
     */
    private final Executor taskExecutor;

    /**
     * 调度事件 observer。
     */
    private final ScheduledTaskObserver observer;

    /**
     * 本地运行参数。
     */
    private final LocalManagedSchedulerOptions options;

    /**
     * 墙钟，仅用于观测时间。
     */
    private final Clock clock;

    /**
     * 单调时间源，仅用于相对调度与耗时。
     */
    private final LongSupplier nanoTime;

    /**
     * taskId、executionId 与 traceId 生成器。
     */
    private final Supplier<String> idSupplier;

    /**
     * Timer 资源工厂。
     */
    private final TimerFactory timerFactory;

    /**
     * 活动任务注册表。
     */
    private final ConcurrentHashMap<String, TaskControl> activeTasks = new ConcurrentHashMap<>();

    /**
     * 活动任务槽位。
     */
    private final Semaphore taskSlots;

    /**
     * 业务在途槽位。
     */
    private final Semaphore inFlightSlots;

    /**
     * 当前在途业务执行数量。
     */
    private final AtomicInteger inFlightCount = new AtomicInteger();

    /**
     * 停止等待通知锁。
     */
    private final Object inFlightMonitor = new Object();

    /**
     * 当前运行 generation；每次 start/stop 单调递增。
     */
    private final AtomicLong generation = new AtomicLong();

    /**
     * 是否接受新任务和 timer 提交。
     */
    private final AtomicBoolean accepting = new AtomicBoolean();

    /**
     * 当前 timer 资源。
     */
    private volatile ScheduledThreadPoolExecutor timerExecutor;

    /**
     * 创建本地受管定时任务运行时。
     *
     * @param taskExecutor 受管且非内联的 background executor；不可为空；调用方负责关闭。
     * @param observer 调度事件 observer；不可为空。
     * @param options 本地运行参数；不可为空。
     * @throws NullPointerException 当任一参数为空时抛出。
     */
    public LocalManagedScheduler(
            final Executor taskExecutor,
            final ScheduledTaskObserver observer,
            final LocalManagedSchedulerOptions options) {
        this(
                taskExecutor,
                observer,
                options,
                Clock.systemUTC(),
                System::nanoTime,
                () -> UUID.randomUUID().toString(),
                LocalManagedScheduler::createTimer);
    }

    /**
     * 创建可注入时间和 timer 的本地调度器测试实例。
     *
     * @param taskExecutor 受管且非内联执行器。
     * @param observer 调度事件 observer。
     * @param options 运行参数。
     * @param clock 观测墙钟。
     * @param nanoTime 单调时间源。
     * @param idSupplier 标识生成器。
     * @param timerFactory timer 资源工厂。
     */
    LocalManagedScheduler(
            final Executor taskExecutor,
            final ScheduledTaskObserver observer,
            final LocalManagedSchedulerOptions options,
            final Clock clock,
            final LongSupplier nanoTime,
            final Supplier<String> idSupplier,
            final TimerFactory timerFactory) {
        this.taskExecutor = Objects.requireNonNull(taskExecutor, "taskExecutor");
        this.observer = Objects.requireNonNull(observer, "observer");
        this.options = Objects.requireNonNull(options, "options");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.idSupplier = Objects.requireNonNull(idSupplier, "idSupplier");
        this.timerFactory = Objects.requireNonNull(timerFactory, "timerFactory");
        this.taskSlots = new Semaphore(options.maxTasks());
        this.inFlightSlots = new Semaphore(options.maxInFlight());
    }

    /**
     * 安排一次性延迟任务。
     *
     * @param definition 任务定义；不可为空。
     * @param delay 延迟；不可为空、不得为负数。
     * @param task 异步任务；不可为空。
     * @return 任务句柄；不可为空、线程安全。
     * @throws ZeroException 当参数、状态、容量或 timer 非法时抛出。
     */
    @Override
    public ScheduledTaskHandle scheduleOnce(
            final ScheduledTaskDefinition definition,
            final Duration delay,
            final ScheduledTask task) {
        long initialDelayNanos = requireNanos(delay, false, "delay");
        return schedule(definition, ScheduleType.ONCE, initialDelayNanos, 0L, task);
    }

    /**
     * 安排固定延迟周期任务。
     *
     * @param definition 任务定义；不可为空。
     * @param initialDelay 首次延迟；不可为空、不得为负数。
     * @param delay 完成后延迟；不可为空且必须大于零。
     * @param task 异步任务；不可为空。
     * @return 任务句柄；不可为空、线程安全。
     * @throws ZeroException 当参数、状态、容量或 timer 非法时抛出。
     */
    @Override
    public ScheduledTaskHandle scheduleWithFixedDelay(
            final ScheduledTaskDefinition definition,
            final Duration initialDelay,
            final Duration delay,
            final ScheduledTask task) {
        long initialDelayNanos = requireNanos(initialDelay, false, "initialDelay");
        long intervalNanos = requireNanos(delay, true, "delay");
        return schedule(definition, ScheduleType.FIXED_DELAY, initialDelayNanos, intervalNanos, task);
    }

    /**
     * 安排固定频率周期任务。
     *
     * @param definition 任务定义；不可为空。
     * @param initialDelay 首次延迟；不可为空、不得为负数。
     * @param period 固定周期；不可为空且必须大于零。
     * @param task 异步任务；不可为空。
     * @return 任务句柄；不可为空、线程安全。
     * @throws ZeroException 当参数、状态、容量或 timer 非法时抛出。
     */
    @Override
    public ScheduledTaskHandle scheduleAtFixedRate(
            final ScheduledTaskDefinition definition,
            final Duration initialDelay,
            final Duration period,
            final ScheduledTask task) {
        long initialDelayNanos = requireNanos(initialDelay, false, "initialDelay");
        long intervalNanos = requireNanos(period, true, "period");
        return schedule(definition, ScheduleType.FIXED_RATE, initialDelayNanos, intervalNanos, task);
    }

    /**
     * 返回当前活动任务快照。
     *
     * @return 按 taskId 排序的不可变、可能为空、线程安全快照列表。
     */
    @Override
    public List<ScheduledTaskSnapshot> snapshots() {
        return activeTasks.values().stream()
                .sorted(Comparator.comparing(TaskControl::taskId))
                .map(TaskControl::snapshot)
                .toList();
    }

    /**
     * 创建单 timer 资源并开始接受任务。
     */
    @Override
    protected void doStart() {
        if (!activeTasks.isEmpty() || inFlightCount.get() != 0) {
            throw ZeroException.of(
                    SchedulerErrorCode.NOT_RUNNING,
                    "scheduler cannot restart while previous tasks are still active",
                    null);
        }
        timerExecutor = Objects.requireNonNull(timerFactory.create(options), "timerExecutor");
        generation.incrementAndGet();
        accepting.set(true);
    }

    /**
     * 停止接收、取消未来 timer、等待在途任务并关闭 timer 资源。
     */
    @Override
    protected void doStop() {
        accepting.set(false);
        generation.incrementAndGet();
        activeTasks.values().forEach(TaskControl::cancelByScheduler);
        ScheduledThreadPoolExecutor currentTimer = timerExecutor;
        timerExecutor = null;
        if (currentTimer != null) {
            currentTimer.shutdownNow();
        }
        awaitStopped(currentTimer);
    }

    private ScheduledTaskHandle schedule(
            final ScheduledTaskDefinition definition,
            final ScheduleType scheduleType,
            final long initialDelayNanos,
            final long intervalNanos,
            final ScheduledTask task) {
        ensureAccepting();
        ScheduledTaskDefinition checkedDefinition = Objects.requireNonNull(definition, "definition");
        ScheduledTask checkedTask = Objects.requireNonNull(task, "task");
        if (!taskSlots.tryAcquire()) {
            throw ZeroException.of(
                    SchedulerErrorCode.TASK_LIMIT_EXCEEDED,
                    "scheduler active task limit exceeded: " + options.maxTasks(),
                    null);
        }
        TaskControl control = null;
        try {
            control = createControl(
                    checkedDefinition,
                    scheduleType,
                    intervalNanos,
                    checkedTask,
                    generation.get());
            armTimer(control, nanoTime.getAsLong() + initialDelayNanos, initialDelayNanos, true);
            emitRegistration(control);
            return control;
        } catch (RuntimeException ex) {
            ZeroException failure = asZeroException(ex, SchedulerErrorCode.TIMER_REJECTED);
            if (control == null) {
                taskSlots.release();
            } else {
                control.rejectInitial(failure);
            }
            throw failure;
        }
    }

    private TaskControl createControl(
            final ScheduledTaskDefinition definition,
            final ScheduleType scheduleType,
            final long intervalNanos,
            final ScheduledTask task,
            final long currentGeneration) {
        for (int attempt = 0; attempt < 16; attempt++) {
            String taskId = newId("taskId");
            TaskControl control = new TaskControl(
                    taskId,
                    definition,
                    scheduleType,
                    intervalNanos,
                    task,
                    currentGeneration);
            if (activeTasks.putIfAbsent(taskId, control) == null) {
                return control;
            }
        }
        throw ZeroException.of(
                SchedulerErrorCode.INVALID_ARGUMENT,
                "scheduler could not generate a unique taskId",
                null);
    }

    private void armTimer(
            final TaskControl control,
            final long deadlineNanos,
            final long delayNanos,
            final boolean initial) {
        if (!valid(control)) {
            if (initial) {
                throw ZeroException.of(SchedulerErrorCode.NOT_RUNNING);
            }
            return;
        }
        ScheduledThreadPoolExecutor currentTimer = timerExecutor;
        if (currentTimer == null) {
            throw ZeroException.of(SchedulerErrorCode.NOT_RUNNING);
        }
        Instant scheduledAt = plusNanos(clock.instant(), delayNanos);
        ScheduledFuture<?> future = currentTimer.schedule(
                () -> onTimer(control, deadlineNanos, scheduledAt),
                delayNanos,
                TimeUnit.NANOSECONDS);
        control.replaceTimer(future, scheduledAt);
    }

    private void onTimer(
            final TaskControl control,
            final long deadlineNanos,
            final Instant scheduledAt) {
        control.clearElapsedTimer();
        if (!valid(control)) {
            return;
        }
        if (control.scheduleType() == ScheduleType.FIXED_RATE
                && !rearmFixedRate(control, deadlineNanos)) {
            return;
        }
        tryDispatch(control, scheduledAt);
    }

    private boolean rearmFixedRate(final TaskControl control, final long deadlineNanos) {
        long now = nanoTime.getAsLong();
        long lateness = now - deadlineNanos;
        long missed = lateness >= 0L ? lateness / control.intervalNanos() : 0L;
        if (missed > 0L) {
            control.recordLateSkip(missed);
        }
        long nextDeadline = lateness >= 0L
                ? now + (control.intervalNanos() - (lateness % control.intervalNanos()))
                : deadlineNanos + control.intervalNanos();
        long delay = Math.max(0L, nextDeadline - now);
        try {
            armTimer(control, nextDeadline, delay, false);
            return true;
        } catch (RuntimeException ex) {
            control.terminateTimerFailure(ex);
            return false;
        }
    }

    private void tryDispatch(final TaskControl control, final Instant scheduledAt) {
        if (control.internalState() != InternalState.SCHEDULED) {
            if (!control.terminal()) {
                control.recordRunningSkip(1L);
            }
            return;
        }
        if (!inFlightSlots.tryAcquire()) {
            onCapacityUnavailable(control);
            return;
        }
        if (!control.markSubmitted()) {
            inFlightSlots.release();
            if (!control.terminal()) {
                control.recordRunningSkip(1L);
            }
            return;
        }
        inFlightCount.incrementAndGet();
        ExecutionLease lease = new ExecutionLease();
        try {
            taskExecutor.execute(() -> startExecution(control, scheduledAt, lease));
        } catch (RuntimeException ex) {
            lease.release();
            control.onExecutorRejected(ex);
        }
    }

    private void onCapacityUnavailable(final TaskControl control) {
        if (control.scheduleType() == ScheduleType.ONCE) {
            control.rejectOnce(SchedulerErrorCode.EXECUTOR_REJECTED, "capacity");
            return;
        }
        control.recordCapacitySkip(1L);
        if (control.scheduleType() == ScheduleType.FIXED_DELAY) {
            rearmFixedDelay(control);
        }
    }

    private void startExecution(
            final TaskControl control,
            final Instant scheduledAt,
            final ExecutionLease lease) {
        if (!valid(control) || !control.markRunning()) {
            lease.release();
            control.cancelIfRequested();
            return;
        }
        long startedNanos;
        ScheduledTaskContext context;
        try {
            startedNanos = nanoTime.getAsLong();
            String executionId = newId("executionId");
            String traceId = newId("traceId");
            Instant startedAt = clock.instant();
            context = new ScheduledTaskContext(
                    control.taskName(),
                    control.taskId(),
                    executionId,
                    traceId,
                    control.nextRunSequence(),
                    scheduledAt,
                    startedAt);
        } catch (Error error) {
            failExecutionSetup(control, error, lease);
            throw error;
        } catch (RuntimeException ex) {
            failExecutionSetup(control, ex, lease);
            return;
        }
        try {
            emitExecutionEvent(
                    control,
                    context,
                    ScheduledTaskEventType.STARTED,
                    Duration.ZERO,
                    SystemErrorCode.OK,
                    "");
            CompletionStage<Void> stage = Objects.requireNonNull(
                    control.task().execute(context),
                    "scheduled task result");
            stage.whenComplete((ignored, failure) -> finishExecution(
                    control,
                    context,
                    startedNanos,
                    failure,
                    false,
                    lease));
        } catch (Error error) {
            finishExecution(control, context, startedNanos, error, true, lease);
            throw error;
        } catch (RuntimeException ex) {
            finishExecution(control, context, startedNanos, ex, false, lease);
        }
    }

    private void finishExecution(
            final TaskControl control,
            final ScheduledTaskContext context,
            final long startedNanos,
            final Throwable throwable,
            final boolean fatal,
            final ExecutionLease lease) {
        if (!lease.beginCompletion()) {
            return;
        }
        Throwable failure = unwrap(throwable);
        Duration elapsed = elapsedSince(startedNanos);
        try {
            if (failure == null) {
                control.onSuccess(context, elapsed);
            } else {
                control.onFailure(context, elapsed, failure, fatal || failure instanceof Error);
            }
        } finally {
            lease.release();
        }
    }

    private void failExecutionSetup(
            final TaskControl control,
            final Throwable failure,
            final ExecutionLease lease) {
        if (!lease.beginCompletion()) {
            return;
        }
        try {
            control.onExecutionSetupFailure(failure);
        } finally {
            lease.release();
        }
    }

    private void rearmFixedDelay(final TaskControl control) {
        if (!valid(control) || control.internalState() != InternalState.SCHEDULED) {
            control.cancelIfRequested();
            return;
        }
        long delay = control.intervalNanos();
        try {
            armTimer(control, nanoTime.getAsLong() + delay, delay, false);
        } catch (RuntimeException ex) {
            control.terminateTimerFailure(ex);
        }
    }

    private void emitRegistration(final TaskControl control) {
        dispatchObservation(new ScheduledTaskEvent(
                clock.instant(),
                ScheduledTaskEventType.REGISTERED,
                control.scheduleType(),
                control.taskName(),
                control.taskId(),
                "",
                newObservationTraceId(),
                0L,
                Optional.empty(),
                1L,
                Duration.ZERO,
                SystemErrorCode.OK,
                ""));
    }

    private void emitExecutionEvent(
            final TaskControl control,
            final ScheduledTaskContext context,
            final ScheduledTaskEventType eventType,
            final Duration elapsed,
            final ErrorCode errorCode,
            final String reason) {
        dispatchObservation(new ScheduledTaskEvent(
                clock.instant(),
                eventType,
                control.scheduleType(),
                control.taskName(),
                control.taskId(),
                context.executionId(),
                context.traceId(),
                context.runSequence(),
                Optional.of(new ScheduledTaskExecutionTiming(
                        context.scheduledAt(),
                        context.startedAt())),
                1L,
                elapsed,
                errorCode,
                reason));
    }

    private void emitControlEvent(
            final TaskControl control,
            final ScheduledTaskEventType eventType,
            final long occurrenceCount,
            final ErrorCode errorCode,
            final String reason) {
        dispatchObservation(new ScheduledTaskEvent(
                clock.instant(),
                eventType,
                control.scheduleType(),
                control.taskName(),
                control.taskId(),
                "",
                newObservationTraceId(),
                0L,
                Optional.empty(),
                occurrenceCount,
                Duration.ZERO,
                errorCode,
                reason));
    }

    private void dispatchObservation(final ScheduledTaskEvent event) {
        try {
            taskExecutor.execute(() -> observe(event));
        } catch (RuntimeException ex) {
            fallbackObserverFailure(event, ex);
        }
    }

    private void observe(final ScheduledTaskEvent event) {
        try {
            observer.onEvent(event);
        } catch (RuntimeException ex) {
            fallbackObserverFailure(event, ex);
        } catch (Error error) {
            fallbackObserverFailure(event, error);
            throw error;
        }
    }

    private void fallbackObserverFailure(final ScheduledTaskEvent event, final Throwable failure) {
        try {
            FALLBACK_LOGGER.log(
                    System.Logger.Level.ERROR,
                    SchedulerErrorCode.OBSERVER_FAILED.code()
                            + " task=" + event.taskName()
                            + " event=" + event.eventType()
                            + " cause=" + failure.getClass().getSimpleName());
        } catch (RuntimeException ignored) {
            // fallback logger 失败时不能递归调用 observer；原始调度状态已经安全落定。
        }
    }

    private void fallbackSystemFailure(
            final ErrorCode errorCode,
            final TaskControl control,
            final Throwable failure) {
        try {
            FALLBACK_LOGGER.log(
                    System.Logger.Level.ERROR,
                    errorCode.code()
                            + " task=" + control.taskName()
                            + " cause=" + failure.getClass().getSimpleName());
        } catch (RuntimeException ignored) {
            // fallback logger 失败时不能递归进入 observer；任务状态已经由调用方安全落定。
        }
    }

    private void awaitStopped(final ScheduledThreadPoolExecutor currentTimer) {
        long timeoutNanos = options.stopTimeout().toNanos();
        long deadline = System.nanoTime() + timeoutNanos;
        boolean timerStopped = awaitTimer(currentTimer, deadline);
        boolean tasksStopped = awaitInFlight(deadline);
        if (!timerStopped || !tasksStopped) {
            activeTasks.values().forEach(TaskControl::emitStopTimeout);
            throw ZeroException.of(
                    SchedulerErrorCode.STOP_TIMEOUT,
                    "scheduler stop timed out with inFlight=" + inFlightCount.get(),
                    null);
        }
    }

    private boolean awaitTimer(
            final ScheduledThreadPoolExecutor currentTimer,
            final long deadlineNanos) {
        if (currentTimer == null) {
            return true;
        }
        long remaining = Math.max(0L, deadlineNanos - System.nanoTime());
        try {
            return currentTimer.awaitTermination(remaining, TimeUnit.NANOSECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw ZeroException.of(
                    SchedulerErrorCode.STOP_TIMEOUT,
                    "scheduler timer stop was interrupted",
                    ex);
        }
    }

    private boolean awaitInFlight(final long deadlineNanos) {
        synchronized (inFlightMonitor) {
            while (inFlightCount.get() > 0) {
                long remaining = deadlineNanos - System.nanoTime();
                if (remaining <= 0L) {
                    return false;
                }
                try {
                    TimeUnit.NANOSECONDS.timedWait(inFlightMonitor, remaining);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw ZeroException.of(
                            SchedulerErrorCode.STOP_TIMEOUT,
                            "scheduler in-flight wait was interrupted",
                            ex);
                }
            }
            return true;
        }
    }

    private boolean valid(final TaskControl control) {
        return accepting.get()
                && control.generation() == generation.get()
                && !control.cancelRequested()
                && !control.terminal();
    }

    private void ensureAccepting() {
        if (!running() || !accepting.get() || timerExecutor == null) {
            throw ZeroException.of(SchedulerErrorCode.NOT_RUNNING);
        }
    }

    private long requireNanos(final Duration value, final boolean positive, final String name) {
        Duration current = Objects.requireNonNull(value, name);
        if (current.isNegative() || (positive && current.isZero())) {
            throw ZeroException.of(
                    SchedulerErrorCode.INVALID_ARGUMENT,
                    name + (positive ? " must be positive" : " must not be negative"),
                    null);
        }
        try {
            return current.toNanos();
        } catch (ArithmeticException ex) {
            throw ZeroException.of(
                    SchedulerErrorCode.INVALID_ARGUMENT,
                    name + " exceeds nanosecond range",
                    ex);
        }
    }

    private String newId(final String name) {
        String value = Objects.requireNonNull(idSupplier.get(), name);
        if (value.isBlank()) {
            throw ZeroException.of(
                    SchedulerErrorCode.INVALID_ARGUMENT,
                    name + " supplier returned blank value",
                    null);
        }
        return value;
    }

    private String newObservationTraceId() {
        return UUID.randomUUID().toString();
    }

    private Instant plusNanos(final Instant instant, final long nanos) {
        try {
            return instant.plusNanos(nanos);
        } catch (DateTimeException ex) {
            return Instant.MAX;
        }
    }

    private Duration elapsedSince(final long startedNanos) {
        return Duration.ofNanos(Math.max(0L, nanoTime.getAsLong() - startedNanos));
    }

    private Throwable unwrap(final Throwable throwable) {
        Throwable current = throwable;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private ZeroException asZeroException(final Throwable throwable, final ErrorCode fallback) {
        if (throwable instanceof ZeroException zeroException) {
            return zeroException;
        }
        String message = throwable.getMessage() == null ? fallback.message() : throwable.getMessage();
        return ZeroException.of(fallback, message, throwable);
    }

    private static ScheduledThreadPoolExecutor createTimer(final LocalManagedSchedulerOptions options) {
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, options.threadNamePrefix() + "-timer-1");
            thread.setDaemon(true);
            return thread;
        };
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, threadFactory);
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        return executor;
    }

    /**
     * 单个任务内部状态控制器。
     *
     * @author zn
     */
    private final class TaskControl implements ScheduledTaskHandle {

        /**
         * 注册 handle 标识。
         */
        private final String taskId;

        /**
         * 任务定义。
         */
        private final ScheduledTaskDefinition definition;

        /**
         * 调度类型。
         */
        private final ScheduleType scheduleType;

        /**
         * 周期间隔纳秒；once 为 0。
         */
        private final long intervalNanos;

        /**
         * 用户异步任务。
         */
        private final ScheduledTask task;

        /**
         * 注册时 scheduler generation。
         */
        private final long generation;

        /**
         * 内部状态。
         */
        private final AtomicReference<InternalState> state = new AtomicReference<>(InternalState.SCHEDULED);

        /**
         * 是否已经请求取消。
         */
        private final AtomicBoolean cancelRequested = new AtomicBoolean();

        /**
         * 是否已经执行终态清理。
         */
        private final AtomicBoolean terminal = new AtomicBoolean();

        /**
         * 当前尚未到期的 timer future。
         */
        private final AtomicReference<ScheduledFuture<?>> timerFuture = new AtomicReference<>();

        /**
         * 实际执行序号和次数。
         */
        private final AtomicLong runSequence = new AtomicLong();

        /**
         * 成功次数。
         */
        private final AtomicLong successCount = new AtomicLong();

        /**
         * 失败次数。
         */
        private final AtomicLong failureCount = new AtomicLong();

        /**
         * 累计运行中跳过次数。
         */
        private final AtomicLong skippedRunningCount = new AtomicLong();

        /**
         * 累计容量跳过次数。
         */
        private final AtomicLong skippedCapacityCount = new AtomicLong();

        /**
         * 累计 timer 迟到跳过次数。
         */
        private final AtomicLong skippedLateCount = new AtomicLong();

        /**
         * 待输出运行中跳过摘要数量。
         */
        private final AtomicLong pendingRunningSkips = new AtomicLong();

        /**
         * 待输出容量跳过摘要数量。
         */
        private final AtomicLong pendingCapacitySkips = new AtomicLong();

        /**
         * 待输出 timer 迟到摘要数量。
         */
        private final AtomicLong pendingLateSkips = new AtomicLong();

        /**
         * 拒绝次数。
         */
        private final AtomicLong rejectionCount = new AtomicLong();

        /**
         * 下一次计划观测时间。
         */
        private final AtomicReference<Instant> nextScheduledAt = new AtomicReference<>();

        /**
         * 最近完成观测时间。
         */
        private final AtomicReference<Instant> lastCompletedAt = new AtomicReference<>();

        /**
         * 最近错误码。
         */
        private final AtomicReference<ErrorCode> lastErrorCode = new AtomicReference<>();

        private TaskControl(
                final String taskId,
                final ScheduledTaskDefinition definition,
                final ScheduleType scheduleType,
                final long intervalNanos,
                final ScheduledTask task,
                final long generation) {
            this.taskId = taskId;
            this.definition = definition;
            this.scheduleType = scheduleType;
            this.intervalNanos = intervalNanos;
            this.task = task;
            this.generation = generation;
        }

        /**
         * 返回 taskId。
         *
         * @return taskId；不可为空；线程安全。
         */
        @Override
        public String taskId() {
            return taskId;
        }

        /**
         * 返回任务名。
         *
         * @return 任务名；不可为空；线程安全。
         */
        @Override
        public String taskName() {
            return definition.taskName();
        }

        /**
         * 返回不可变状态快照。
         *
         * @return 快照；不可为空、线程安全。
         */
        @Override
        public ScheduledTaskSnapshot snapshot() {
            return new ScheduledTaskSnapshot(
                    taskId,
                    taskName(),
                    scheduleType,
                    publicState(state.get()),
                    runSequence.get(),
                    successCount.get(),
                    failureCount.get(),
                    skippedRunningCount.get(),
                    skippedCapacityCount.get(),
                    skippedLateCount.get(),
                    rejectionCount.get(),
                    java.util.Optional.ofNullable(nextScheduledAt.get()),
                    java.util.Optional.ofNullable(lastCompletedAt.get()),
                    java.util.Optional.ofNullable(lastErrorCode.get()));
        }

        /**
         * 幂等取消未来调度。
         *
         * @return true 表示首次请求取消；线程安全。
         */
        @Override
        public boolean cancel() {
            return requestCancel("caller");
        }

        private void cancelByScheduler() {
            requestCancel("scheduler-stop");
            flushSkipSummaries();
        }

        private void emitStopTimeout() {
            lastErrorCode.set(SchedulerErrorCode.STOP_TIMEOUT);
            emitControlEvent(
                    this,
                    ScheduledTaskEventType.STOP_TIMEOUT,
                    1L,
                    SchedulerErrorCode.STOP_TIMEOUT,
                    "stop-timeout");
        }

        private boolean requestCancel(final String reason) {
            if (terminal.get() || !cancelRequested.compareAndSet(false, true)) {
                return false;
            }
            ScheduledFuture<?> current = timerFuture.getAndSet(null);
            if (current != null) {
                current.cancel(false);
            }
            if (transitionToTerminal(InternalState.CANCELLED, false)) {
                emitControlEvent(
                        this,
                        ScheduledTaskEventType.CANCELLED,
                        1L,
                        SystemErrorCode.OK,
                        reason);
                flushSkipSummaries();
            }
            return true;
        }

        private void replaceTimer(
                final ScheduledFuture<?> future,
                final Instant scheduledAt) {
            ScheduledFuture<?> previous = timerFuture.getAndSet(future);
            if (previous != null && !previous.isDone()) {
                previous.cancel(false);
            }
            nextScheduledAt.set(scheduledAt);
            if (!valid(this)) {
                future.cancel(false);
                timerFuture.compareAndSet(future, null);
                nextScheduledAt.compareAndSet(scheduledAt, null);
            }
        }

        private void clearElapsedTimer() {
            timerFuture.set(null);
            nextScheduledAt.set(null);
        }

        private boolean markSubmitted() {
            return valid(this) && state.compareAndSet(InternalState.SCHEDULED, InternalState.SUBMITTED);
        }

        private boolean markRunning() {
            return valid(this) && state.compareAndSet(InternalState.SUBMITTED, InternalState.RUNNING);
        }

        private long nextRunSequence() {
            return runSequence.incrementAndGet();
        }

        private void onSuccess(final ScheduledTaskContext context, final Duration elapsed) {
            successCount.incrementAndGet();
            lastCompletedAt.set(clock.instant());
            emitExecutionEvent(this, context, ScheduledTaskEventType.SUCCEEDED, elapsed, SystemErrorCode.OK, "");
            if (cancelRequested.get() || generation != LocalManagedScheduler.this.generation.get()) {
                terminateAfterRun(InternalState.CANCELLED, "cancelled-after-run");
            } else if (scheduleType == ScheduleType.ONCE) {
                transitionToTerminal(InternalState.COMPLETED, true);
            } else {
                continuePeriodic();
            }
            flushSkipSummaries();
        }

        private void onFailure(
                final ScheduledTaskContext context,
                final Duration elapsed,
                final Throwable failure,
                final boolean fatal) {
            failureCount.incrementAndGet();
            lastCompletedAt.set(clock.instant());
            ErrorCode errorCode = failure instanceof ZeroException zeroException
                    ? zeroException.errorCode()
                    : SchedulerErrorCode.TASK_EXECUTION_FAILED;
            lastErrorCode.set(errorCode);
            emitExecutionEvent(
                    this,
                    context,
                    ScheduledTaskEventType.FAILED,
                    elapsed,
                    errorCode,
                    failure.getClass().getSimpleName());
            if (cancelRequested.get() || generation != LocalManagedScheduler.this.generation.get()) {
                terminateAfterRun(InternalState.CANCELLED, "cancelled-after-failure");
            } else if (fatal
                    || scheduleType == ScheduleType.ONCE
                    || definition.failurePolicy() == ScheduledTaskFailurePolicy.CANCEL_ON_FAILURE) {
                transitionToTerminal(InternalState.FAILED, true);
            } else {
                continuePeriodic();
            }
            flushSkipSummaries();
        }

        private void onExecutionSetupFailure(final Throwable failure) {
            failureCount.incrementAndGet();
            lastCompletedAt.set(clock.instant());
            lastErrorCode.set(SchedulerErrorCode.TASK_EXECUTION_FAILED);
            emitControlEvent(
                    this,
                    ScheduledTaskEventType.FAILED,
                    1L,
                    SchedulerErrorCode.TASK_EXECUTION_FAILED,
                    "execution-setup");
            if (cancelRequested.get() || generation != LocalManagedScheduler.this.generation.get()) {
                terminateAfterRun(InternalState.CANCELLED, "cancelled-during-execution-setup");
            } else {
                transitionToTerminal(InternalState.FAILED, true);
            }
            fallbackSystemFailure(SchedulerErrorCode.TASK_EXECUTION_FAILED, this, failure);
            flushSkipSummaries();
        }

        private void continuePeriodic() {
            if (!state.compareAndSet(InternalState.RUNNING, InternalState.SCHEDULED)) {
                cancelIfRequested();
                return;
            }
            if (cancelRequested.get() || generation != LocalManagedScheduler.this.generation.get()) {
                terminateAfterRun(InternalState.CANCELLED, "cancelled-before-rearm");
            } else if (scheduleType == ScheduleType.FIXED_DELAY) {
                rearmFixedDelay(this);
            }
        }

        private void terminateAfterRun(final InternalState terminalState, final String reason) {
            boolean transitioned = transitionToTerminal(terminalState, true);
            if (transitioned && terminalState == InternalState.CANCELLED) {
                emitControlEvent(this, ScheduledTaskEventType.CANCELLED, 1L, SystemErrorCode.OK, reason);
            }
        }

        private void cancelIfRequested() {
            if (cancelRequested.get()) {
                transitionToTerminal(InternalState.CANCELLED, false);
            }
        }

        private boolean transitionToTerminal(
                final InternalState targetState,
                final boolean includeRunning) {
            while (true) {
                InternalState current = state.get();
                if (terminalState(current) || (!includeRunning && current == InternalState.RUNNING)) {
                    return false;
                }
                if (state.compareAndSet(current, targetState)) {
                    finishTerminal();
                    return true;
                }
            }
        }

        private void onExecutorRejected(final RuntimeException failure) {
            if (terminal.get() || cancelRequested.get()) {
                fallbackSystemFailure(SchedulerErrorCode.EXECUTOR_REJECTED, this, failure);
                return;
            }
            if (scheduleType == ScheduleType.ONCE) {
                rejectOnce(SchedulerErrorCode.EXECUTOR_REJECTED, "executor");
            } else if (state.compareAndSet(InternalState.SUBMITTED, InternalState.SCHEDULED)) {
                rejectionCount.incrementAndGet();
                lastErrorCode.set(SchedulerErrorCode.EXECUTOR_REJECTED);
                recordCapacitySkip(1L);
                if (scheduleType == ScheduleType.FIXED_DELAY) {
                    rearmFixedDelay(this);
                }
            }
            fallbackSystemFailure(SchedulerErrorCode.EXECUTOR_REJECTED, this, failure);
        }

        private void rejectOnce(final ErrorCode errorCode, final String reason) {
            if (cancelRequested.get()) {
                cancelIfRequested();
                return;
            }
            if (transitionToTerminal(InternalState.REJECTED, false)) {
                rejectionCount.incrementAndGet();
                lastErrorCode.set(errorCode);
                emitControlEvent(this, ScheduledTaskEventType.REJECTED, 1L, errorCode, reason);
                flushSkipSummaries();
            }
        }

        private void rejectInitial(final ZeroException failure) {
            if (transitionToTerminal(InternalState.REJECTED, false)) {
                lastErrorCode.set(failure.errorCode());
            }
        }

        private void terminateTimerFailure(final RuntimeException failure) {
            if (transitionToTerminal(InternalState.REJECTED, false)) {
                rejectionCount.incrementAndGet();
                lastErrorCode.set(SchedulerErrorCode.TIMER_REJECTED);
                emitControlEvent(
                        this,
                        ScheduledTaskEventType.REJECTED,
                        1L,
                        SchedulerErrorCode.TIMER_REJECTED,
                        "timer");
                flushSkipSummaries();
            }
            fallbackSystemFailure(SchedulerErrorCode.TIMER_REJECTED, this, failure);
        }

        private void recordRunningSkip(final long count) {
            addSaturated(skippedRunningCount, count);
            addSaturated(pendingRunningSkips, count);
        }

        private void recordCapacitySkip(final long count) {
            addSaturated(skippedCapacityCount, count);
            addSaturated(pendingCapacitySkips, count);
        }

        private void recordLateSkip(final long count) {
            addSaturated(skippedLateCount, count);
            addSaturated(pendingLateSkips, count);
        }

        private void flushSkipSummaries() {
            flushSkip(pendingRunningSkips, ScheduledTaskEventType.SKIPPED_RUNNING, "running");
            flushSkip(pendingCapacitySkips, ScheduledTaskEventType.SKIPPED_CAPACITY, "capacity");
            flushSkip(pendingLateSkips, ScheduledTaskEventType.SKIPPED_LATE, "late");
        }

        private void flushSkip(
                final AtomicLong pending,
                final ScheduledTaskEventType eventType,
                final String reason) {
            long count = pending.getAndSet(0L);
            if (count > 0L) {
                emitControlEvent(this, eventType, count, SystemErrorCode.OK, reason);
            }
        }

        private void finishTerminal() {
            if (!terminal.compareAndSet(false, true)) {
                return;
            }
            ScheduledFuture<?> current = timerFuture.getAndSet(null);
            if (current != null) {
                current.cancel(false);
            }
            nextScheduledAt.set(null);
            activeTasks.remove(taskId, this);
            taskSlots.release();
        }

        private InternalState internalState() {
            return state.get();
        }

        private ScheduleType scheduleType() {
            return scheduleType;
        }

        private long intervalNanos() {
            return intervalNanos;
        }

        private ScheduledTask task() {
            return task;
        }

        private long generation() {
            return generation;
        }

        private boolean cancelRequested() {
            return cancelRequested.get();
        }

        private boolean terminal() {
            return terminal.get();
        }
    }

    /**
     * 单次在途预算租约。
     *
     * @author zn
     */
    private final class ExecutionLease {

        /**
         * 是否已经开始完成处理。
         */
        private final AtomicBoolean completionStarted = new AtomicBoolean();

        /**
         * 是否已经释放。
         */
        private final AtomicBoolean released = new AtomicBoolean();

        private boolean beginCompletion() {
            return completionStarted.compareAndSet(false, true);
        }

        private void release() {
            if (!released.compareAndSet(false, true)) {
                return;
            }
            inFlightSlots.release();
            inFlightCount.decrementAndGet();
            synchronized (inFlightMonitor) {
                inFlightMonitor.notifyAll();
            }
        }
    }

    /**
     * 本地任务内部状态。
     *
     * @author zn
     */
    private enum InternalState {

        /**
         * 等待 timer 或下一次触发。
         */
        SCHEDULED,

        /**
         * 已提交 background executor，尚未进入用户代码。
         */
        SUBMITTED,

        /**
         * 用户任务或异步 stage 尚未完成。
         */
        RUNNING,

        /**
         * 已取消。
         */
        CANCELLED,

        /**
         * 一次性任务成功完成。
         */
        COMPLETED,

        /**
         * 失败终止。
         */
        FAILED,

        /**
         * 被容量、timer 或 executor 拒绝。
         */
        REJECTED
    }

    /**
     * 创建 timer 资源的内部工厂。
     *
     * @author zn
     */
    @FunctionalInterface
    interface TimerFactory {

        /**
         * 创建单 timer 资源。
         *
         * @param options 本地运行参数；不可为空。
         * @return timer executor；不可为空；调用方负责关闭。
         */
        ScheduledThreadPoolExecutor create(LocalManagedSchedulerOptions options);
    }

    private static ScheduledTaskState publicState(final InternalState state) {
        return switch (state) {
            case SCHEDULED, SUBMITTED -> ScheduledTaskState.SCHEDULED;
            case RUNNING -> ScheduledTaskState.RUNNING;
            case CANCELLED -> ScheduledTaskState.CANCELLED;
            case COMPLETED -> ScheduledTaskState.COMPLETED;
            case FAILED -> ScheduledTaskState.FAILED;
            case REJECTED -> ScheduledTaskState.REJECTED;
        };
    }

    private static boolean terminalState(final InternalState state) {
        return state == InternalState.CANCELLED
                || state == InternalState.COMPLETED
                || state == InternalState.FAILED
                || state == InternalState.REJECTED;
    }

    private static void addSaturated(final AtomicLong counter, final long delta) {
        if (delta <= 0L) {
            return;
        }
        long previous;
        long updated;
        do {
            previous = counter.get();
            updated = Long.MAX_VALUE - previous < delta ? Long.MAX_VALUE : previous + delta;
        } while (!counter.compareAndSet(previous, updated));
    }
}
