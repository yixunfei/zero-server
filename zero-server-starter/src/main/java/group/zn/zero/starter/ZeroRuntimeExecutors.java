package group.zn.zero.starter;

import group.zn.zero.core.error.SystemErrorCode;
import group.zn.zero.core.error.ZeroException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * starter 运行时执行器装配点。
 *
 * <p>本类提供阶段 3 原型所需的执行域装配边界。默认本地装配仍可使用直接执行器；
 * 原型运行可显式使用 starter 管理的 logic、actor、remote IO 和 background 执行器。
 *
 * @author zn
 */
public final class ZeroRuntimeExecutors implements AutoCloseable {

    /**
     * 默认关闭等待时间。
     */
    private static final Duration DEFAULT_CLOSE_TIMEOUT = Duration.ofSeconds(3);

    /**
     * 逻辑执行器。
     */
    private final Executor logicExecutor;

    /**
     * Actor 执行器。
     */
    private final Executor actorExecutor;

    /**
     * 远程 IO 执行器。
     */
    private final Executor remoteIoExecutor;

    /**
     * 后台任务执行器。
     */
    private final Executor backgroundExecutor;

    /**
     * 由本对象拥有并负责关闭的执行器。
     */
    private final List<ExecutorService> ownedServices;

    /**
     * 关闭等待时间。
     */
    private final Duration closeTimeout;

    /**
     * 远程 IO 执行器是否可能在调用线程内联执行。
     */
    private final boolean remoteIoMayInline;

    /**
     * 后台执行器是否可能在调用线程内联执行。
     */
    private final boolean backgroundMayInline;

    private ZeroRuntimeExecutors(
            final Executor logicExecutor,
            final Executor actorExecutor,
            final Executor remoteIoExecutor,
            final Executor backgroundExecutor,
            final List<ExecutorService> ownedServices,
            final Duration closeTimeout,
            final boolean remoteIoMayInline,
            final boolean backgroundMayInline) {
        this.logicExecutor = Objects.requireNonNull(logicExecutor, "logicExecutor");
        this.actorExecutor = Objects.requireNonNull(actorExecutor, "actorExecutor");
        this.remoteIoExecutor = Objects.requireNonNull(remoteIoExecutor, "remoteIoExecutor");
        this.backgroundExecutor = Objects.requireNonNull(backgroundExecutor, "backgroundExecutor");
        this.ownedServices = List.copyOf(Objects.requireNonNull(ownedServices, "ownedServices"));
        this.ownedServices.forEach(service -> Objects.requireNonNull(service, "service"));
        this.closeTimeout = Objects.requireNonNull(closeTimeout, "closeTimeout");
        this.remoteIoMayInline = remoteIoMayInline;
        this.backgroundMayInline = backgroundMayInline;
    }

    /**
     * 创建直接执行器装配。
     *
     * @return 执行器装配；不可为空；不拥有后台线程。
     */
    public static ZeroRuntimeExecutors direct() {
        return new ZeroRuntimeExecutors(
                Runnable::run,
                Runnable::run,
                Runnable::run,
                Runnable::run,
                List.of(),
                DEFAULT_CLOSE_TIMEOUT,
                true,
                true);
    }

    /**
     * 创建 starter 管理的单线程逻辑执行器装配。
     *
     * <p>该工厂用于兼容完整本地 demo：logic 使用单线程执行器，Actor 仍在调用线程内推进，
     * 避免网络 handler 等待同一个单线程执行器中的 Actor 任务造成自等待。
     *
     * @param threadName 线程名称；不可为空。
     * @return 执行器装配；不可为空；关闭时会停止内部线程池。
     * @throws IllegalArgumentException 当线程名称为空白时抛出。
     */
    public static ZeroRuntimeExecutors singleThreaded(final String threadName) {
        String currentName = Objects.requireNonNull(threadName, "threadName");
        if (currentName.isBlank()) {
            throw new IllegalArgumentException("threadName must not be blank");
        }
        ExecutorService service = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, currentName);
            thread.setDaemon(true);
            return thread;
        });
        return new ZeroRuntimeExecutors(
                service,
                Runnable::run,
                Runnable::run,
                Runnable::run,
                List.of(service),
                DEFAULT_CLOSE_TIMEOUT,
                true,
                true);
    }

    /**
     * 创建阶段 3 本地原型执行器装配。
     *
     * <p>该工厂会创建独立 logic、actor、remote IO 和 background 执行域。业务代码不应直接
     * 创建线程池，应通过本对象获取执行器并由 starter 统一关闭。
     *
     * @param threadNamePrefix 线程名前缀；不可为空。
     * @return 执行器装配；不可为空；关闭时会停止内部线程池。
     * @throws IllegalArgumentException 当前缀为空白时抛出。
     */
    public static ZeroRuntimeExecutors localPrototype(final String threadNamePrefix) {
        int actorThreads = Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors()));
        return localPrototype(threadNamePrefix, actorThreads);
    }

    /**
     * 创建阶段 3 本地原型执行器装配。
     *
     * @param threadNamePrefix 线程名前缀；不可为空。
     * @param actorThreads actor 执行器线程数；必须大于 0。
     * @return 执行器装配；不可为空；关闭时会停止内部线程池。
     * @throws IllegalArgumentException 当前缀为空白或 actorThreads 小于 1 时抛出。
     */
    public static ZeroRuntimeExecutors localPrototype(final String threadNamePrefix, final int actorThreads) {
        String prefix = requireText(threadNamePrefix, "threadNamePrefix");
        if (actorThreads < 1) {
            throw new IllegalArgumentException("actorThreads must be positive");
        }
        ExecutorService logic = Executors.newSingleThreadExecutor(namedThreadFactory(prefix + "-logic-"));
        ExecutorService actor = Executors.newFixedThreadPool(actorThreads, namedThreadFactory(prefix + "-actor-"));
        ExecutorService remoteIo = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name(prefix + "-remote-io-", 0).factory());
        ExecutorService background = Executors.newSingleThreadExecutor(namedThreadFactory(prefix + "-background-"));
        return new ZeroRuntimeExecutors(
                logic,
                actor,
                remoteIo,
                background,
                List.of(logic, actor, remoteIo, background),
                DEFAULT_CLOSE_TIMEOUT,
                false,
                false);
    }

    /**
     * 返回逻辑执行器。
     *
     * @return 逻辑执行器；不可为空；线程安全性由具体实现决定。
     */
    public Executor logicExecutor() {
        return logicExecutor;
    }

    /**
     * 返回 Actor 执行器。
     *
     * <p>该执行器用于 actor-backed 原型调度。返回值不可为空；业务代码不得自行关闭。
     *
     * @return Actor 执行器；不可为空；线程安全性由具体实现决定。
     */
    public Executor actorExecutor() {
        return actorExecutor;
    }

    /**
     * 返回远程 IO 执行器。
     *
     * <p>该执行器用于数据库、RPC、消息队列等不可控远程 IO。返回值不可为空；业务代码不得自行关闭。
     *
     * @return 远程 IO 执行器；不可为空；线程安全性由具体实现决定。
     */
    public Executor remoteIoExecutor() {
        return remoteIoExecutor;
    }

    /**
     * 返回远程 IO 执行器是否可能在调用线程内联执行。
     *
     * <p>生产网络远程鉴权必须要求该值为 false，避免 Netty IO 线程被不可控远程调用阻塞。</p>
     *
     * @return true 表示 remote IO 任务可能直接在提交线程执行；线程安全。
     */
    public boolean remoteIoMayInline() {
        return remoteIoMayInline;
    }

    /**
     * 返回后台任务执行器。
     *
     * <p>该执行器用于低频 GM、配置刷新和后台统计任务。返回值不可为空；业务代码不得自行关闭。
     *
     * @return 后台任务执行器；不可为空；线程安全性由具体实现决定。
     */
    public Executor backgroundExecutor() {
        return backgroundExecutor;
    }

    /**
     * 返回后台执行器是否可能在提交线程内联执行。
     *
     * <p>受管定时任务必须要求该值为 false，避免用户任务、observer、日志或指标在 timer thread
     * 中执行。该方法只读取构造期不可变能力标记，不提交任务、不修改运行时状态。</p>
     *
     * @return true 表示后台任务可能直接在提交线程执行；线程安全。
     */
    public boolean backgroundMayInline() {
        return backgroundMayInline;
    }

    /**
     * 关闭由 starter 拥有的执行器。
     *
     * @throws ZeroException 当等待关闭被中断时抛出，必须绑定 ErrorCode。
     */
    @Override
    public void close() {
        for (ExecutorService service : ownedServices) {
            service.shutdown();
        }
        ZeroException interruptionFailure = null;
        for (ExecutorService service : ownedServices) {
            if (interruptionFailure != null) {
                shutdownNowAfterInterruption(service, interruptionFailure);
                continue;
            }
            try {
                if (!service.awaitTermination(closeTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                    service.shutdownNow();
                }
            } catch (InterruptedException ex) {
                interruptionFailure = ZeroException.of(
                        SystemErrorCode.SYSTEM_ERROR,
                        "close runtime executor interrupted",
                        ex);
                shutdownNowAfterInterruption(service, interruptionFailure);
            }
        }
        if (interruptionFailure != null) {
            Thread.currentThread().interrupt();
            throw interruptionFailure;
        }
    }

    /**
     * 在线程已被中断后立即停止单个执行器；失败会附加到统一中断异常并继续处理后续执行器。
     *
     * @param service 待停止执行器；不可为空。
     * @param interruptionFailure 中断主异常；不可为空，本方法可能追加 suppressed。
     */
    private static void shutdownNowAfterInterruption(
            final ExecutorService service,
            final ZeroException interruptionFailure) {
        try {
            service.shutdownNow();
        } catch (RuntimeException | Error closeFailure) {
            if (closeFailure != interruptionFailure) {
                interruptionFailure.addSuppressed(closeFailure);
            }
        }
    }

    private static ThreadFactory namedThreadFactory(final String prefix) {
        AtomicInteger sequence = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private static String requireText(final String value, final String name) {
        String current = Objects.requireNonNull(value, name);
        if (current.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return current;
    }
}
