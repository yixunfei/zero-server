package group.zn.zero.hotupdate.config;

import group.zn.zero.core.error.ErrorCode;
import group.zn.zero.core.error.ZeroException;
import group.zn.zero.core.lifecycle.AbstractLifecycle;
import group.zn.zero.core.lifecycle.LifecycleState;
import group.zn.zero.hotupdate.HotUpdateRequest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 本地 CSV 配置表初始加载与原子热重载服务。
 *
 * <pre>
 * register definitions
 *   -> start: build all candidates on managed IO executor
 *   -> validate every candidate
 *   -> publish every initial snapshot
 *   -> optional WatchService loop
 *   -> reload: per-table synchronized load / validate / atomic swap
 * </pre>
 *
 * <p>本服务不创建或关闭线程池。调用方必须注入受管 IO executor；启用 watcher 时该 executor
 * 必须非内联并允许长期阻塞任务。运行中只保证单表原子替换，不提供多文件事务或集群一致性。
 *
 * @author zn
 */
public final class LocalConfigHotReloadService extends AbstractLifecycle {

    /**
     * 配置文件 IO 与 watcher 执行器。
     */
    private final Executor ioExecutor;

    /**
     * 自动请求工厂。
     */
    private final ConfigReloadRequestFactory automaticRequestFactory;

    /**
     * 重载观察者。
     */
    private final ConfigReloadObserver observer;

    /**
     * 热重载运行参数。
     */
    private final ConfigHotReloadOptions options;

    /**
     * UTC 时钟。
     */
    private final Clock clock;

    /**
     * 按注册顺序保存的 typed 配置表。
     */
    private final Map<String, ConfigTable<?, ?>> tables = new LinkedHashMap<>();

    /**
     * watcher 运行失败。
     */
    private final AtomicReference<ZeroException> watcherFailure = new AtomicReference<>();

    /**
     * 当前 watcher。
     */
    private volatile LocalConfigFileWatcher watcher;

    /**
     * 创建本地配置热重载服务。
     *
     * @param ioExecutor 受管配置 IO executor；不可为空，服务不会关闭它。
     * @param automaticRequestFactory 初始加载和 watcher 请求工厂；不可为空。
     * @param observer 重载观察者；不可为空。
     * @param options 运行参数；不可为空。
     * @throws NullPointerException 当任一参数为空时抛出。
     */
    public LocalConfigHotReloadService(
            final Executor ioExecutor,
            final ConfigReloadRequestFactory automaticRequestFactory,
            final ConfigReloadObserver observer,
            final ConfigHotReloadOptions options) {
        this(ioExecutor, automaticRequestFactory, observer, options, Clock.systemUTC());
    }

    LocalConfigHotReloadService(
            final Executor ioExecutor,
            final ConfigReloadRequestFactory automaticRequestFactory,
            final ConfigReloadObserver observer,
            final ConfigHotReloadOptions options,
            final Clock clock) {
        this.ioExecutor = Objects.requireNonNull(ioExecutor, "ioExecutor");
        this.automaticRequestFactory = Objects.requireNonNull(automaticRequestFactory, "automaticRequestFactory");
        this.observer = Objects.requireNonNull(observer, "observer");
        this.options = Objects.requireNonNull(options, "options");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 在服务启动前注册 typed 配置表。
     *
     * @param definition 配置表定义；不可为空。
     * @param <K> 配置 key 类型。
     * @param <V> 配置对象类型。
     * @return typed 读取句柄；不可为空，服务启动后可并发读取。
     * @throws ZeroException 当服务已启动或表名重复时抛出。
     */
    public synchronized <K, V> ConfigTable<K, V> register(final ConfigTableDefinition<K, V> definition) {
        if (state() != LifecycleState.NEW) {
            throw ZeroException.of(
                    ConfigReloadErrorCode.INVALID_SERVICE_STATE,
                    "config tables can only be registered before service start",
                    null);
        }
        ConfigTableDefinition<K, V> checkedDefinition = Objects.requireNonNull(definition, "definition");
        if (tables.containsKey(checkedDefinition.tableName())) {
            throw ZeroException.of(
                    ConfigReloadErrorCode.TABLE_ALREADY_REGISTERED,
                    "config table is already registered: " + checkedDefinition.tableName(),
                    null);
        }
        ConfigTable<K, V> table = new ConfigTable<>(checkedDefinition);
        tables.put(checkedDefinition.tableName(), table);
        return table;
    }

    /**
     * 返回已注册表名快照。
     *
     * @return 不可变、有序、可能为空、线程安全的表名集合。
     */
    public synchronized Set<String> registeredTableNames() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(tables.keySet()));
    }

    /**
     * 在受管 IO executor 中显式异步重载单表。
     *
     * @param tableName 稳定表名；不可为空。
     * @param request 热更请求上下文；不可为空。
     * @return 异步结果；不可为空。非法候选正常完成为 `REJECTED`，executor/observer 故障异常完成。
     * @throws ZeroException 当服务未运行或表未注册时抛出。
     */
    public CompletionStage<ConfigReloadResult> reloadAsync(
            final String tableName,
            final HotUpdateRequest request) {
        requireRunning();
        ConfigTable<?, ?> table = requireTable(tableName);
        HotUpdateRequest checkedRequest = Objects.requireNonNull(request, "request");
        try {
            return CompletableFuture.supplyAsync(() -> reloadNow(table, checkedRequest), ioExecutor);
        } catch (RejectedExecutionException ex) {
            return CompletableFuture.failedFuture(ZeroException.of(
                    ConfigReloadErrorCode.EXECUTOR_REJECTED,
                    "config reload task was rejected",
                    ex));
        }
    }

    /**
     * 返回 watcher 是否已失败。
     *
     * @return watcher 故障；为空表示未发现故障；线程安全。
     */
    public Optional<ZeroException> watcherFailure() {
        return Optional.ofNullable(watcherFailure.get());
    }

    /**
     * 在受管 IO executor 上构建全部候选，并在全部成功后发布。
     *
     * @throws ZeroException 当未注册表、候选失败、observer 失败或 watcher 启动失败时抛出。
     */
    @Override
    protected void doStart() {
        List<ConfigTable<?, ?>> currentTables = tablesSnapshot();
        if (currentTables.isEmpty()) {
            throw ZeroException.of(ConfigReloadErrorCode.NO_TABLES_REGISTERED);
        }
        List<ConfigReloadCandidate> candidates = prepareInitialOnIo(currentTables);
        LocalConfigFileWatcher preparedWatcher = prepareWatcher(currentTables);
        try {
            candidates.forEach(ConfigReloadCandidate::publish);
            observeInitial(candidates);
            startWatcher(preparedWatcher);
            watcher = preparedWatcher;
        } catch (RuntimeException | Error ex) {
            rollBack(candidates);
            closePreparedWatcher(preparedWatcher, ex);
            throw ex;
        }
    }

    /**
     * 停止并解除本地 watcher 阻塞；配置快照保持不可变可读。
     */
    @Override
    protected void doStop() {
        LocalConfigFileWatcher currentWatcher = watcher;
        watcher = null;
        if (currentWatcher != null) {
            currentWatcher.close();
        }
    }

    private List<ConfigReloadCandidate> prepareInitialOnIo(final List<ConfigTable<?, ?>> currentTables) {
        try {
            return CompletableFuture.supplyAsync(() -> prepareInitial(currentTables), ioExecutor).join();
        } catch (RejectedExecutionException ex) {
            throw ZeroException.of(
                    ConfigReloadErrorCode.EXECUTOR_REJECTED,
                    "initial config load task was rejected",
                    ex);
        } catch (CompletionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof ZeroException zeroException) {
                throw zeroException;
            }
            throw ZeroException.of(
                    ConfigReloadErrorCode.INITIAL_LOAD_FAILED,
                    "initial config load failed",
                    cause);
        }
    }

    private List<ConfigReloadCandidate> prepareInitial(final List<ConfigTable<?, ?>> currentTables) {
        List<ConfigReloadCandidate> candidates = new ArrayList<>();
        for (ConfigTable<?, ?> table : currentTables) {
            try {
                candidates.add(table.prepareInitial(clock.instant()));
            } catch (RuntimeException ex) {
                observeInitialRejected(table, ex);
                throw ex;
            }
        }
        return List.copyOf(candidates);
    }

    private LocalConfigFileWatcher prepareWatcher(final List<ConfigTable<?, ?>> currentTables) {
        if (!options.watchEnabled()) {
            return null;
        }
        return LocalConfigFileWatcher.prepare(
                currentTables,
                options,
                this::automaticReload,
                this::watcherFailed);
    }

    private void startWatcher(final LocalConfigFileWatcher preparedWatcher) {
        if (preparedWatcher != null) {
            preparedWatcher.start(ioExecutor);
        }
    }

    private void observeInitial(final List<ConfigReloadCandidate> candidates) {
        for (ConfigReloadCandidate candidate : candidates) {
            Instant completedAt = clock.instant();
            HotUpdateRequest request = automaticRequestFactory.create(
                    candidate.tableName(),
                    candidate.version(),
                    completedAt);
            notifyObserver(request, new ConfigReloadResult(
                    candidate.tableName(),
                    candidate.sourceName(),
                    ConfigReloadStatus.LOADED,
                    0,
                    candidate.version(),
                    candidate.checksum(),
                    candidate.rowCount(),
                    Duration.ZERO,
                    completedAt,
                    null));
        }
    }

    private void observeInitialRejected(final ConfigTable<?, ?> table, final RuntimeException cause) {
        Instant completedAt = clock.instant();
        HotUpdateRequest request = automaticRequestFactory.create(table.definition().tableName(), 1, completedAt);
        ConfigReloadResult result = rejectedResult(table, cause, 0, completedAt);
        notifyObserver(request, result);
    }

    private void automaticReload(final String tableName) {
        ConfigTable<?, ?> table = requireTable(tableName);
        Instant requestedAt = clock.instant();
        HotUpdateRequest request = automaticRequestFactory.create(tableName, table.nextVersion(), requestedAt);
        reloadNow(table, request);
    }

    private ConfigReloadResult reloadNow(
            final ConfigTable<?, ?> table,
            final HotUpdateRequest request) {
        long startedNanos = System.nanoTime();
        ConfigTableReloadOutcome outcome;
        try {
            outcome = table.reload(clock.instant());
        } catch (RuntimeException ex) {
            ConfigReloadResult rejected = rejectedResult(
                    table,
                    ex,
                    startedNanos,
                    clock.instant());
            notifyObserver(request, rejected);
            return rejected;
        }
        ConfigReloadResult accepted = acceptedResult(table, outcome, startedNanos, clock.instant());
        notifyObserver(request, accepted);
        return accepted;
    }

    private ConfigReloadResult acceptedResult(
            final ConfigTable<?, ?> table,
            final ConfigTableReloadOutcome outcome,
            final long startedNanos,
            final Instant completedAt) {
        return new ConfigReloadResult(
                table.definition().tableName(),
                table.sourceName(),
                outcome.status(),
                outcome.previousVersion(),
                outcome.currentVersion(),
                outcome.checksum(),
                outcome.rowCount(),
                elapsed(startedNanos),
                completedAt,
                null);
    }

    private ConfigReloadResult rejectedResult(
            final ConfigTable<?, ?> table,
            final RuntimeException cause,
            final long startedNanos,
            final Instant completedAt) {
        ConfigTableSnapshot<?, ?> current = table.currentOrNull();
        long version = current == null ? 0 : current.version();
        return new ConfigReloadResult(
                table.definition().tableName(),
                table.sourceName(),
                ConfigReloadStatus.REJECTED,
                version,
                version,
                current == null ? "" : current.checksum(),
                current == null ? 0 : current.rowCount(),
                elapsed(startedNanos),
                completedAt,
                errorCode(cause));
    }

    private void notifyObserver(final HotUpdateRequest request, final ConfigReloadResult result) {
        try {
            observer.onReload(
                    Objects.requireNonNull(request, "request"),
                    Objects.requireNonNull(result, "result"));
        } catch (RuntimeException ex) {
            throw ZeroException.of(
                    ConfigReloadErrorCode.OBSERVER_FAILED,
                    "config reload observer failed",
                    ex);
        }
    }

    private void watcherFailed(final Throwable cause) {
        ZeroException failure = cause instanceof ZeroException zeroException
                ? zeroException
                : ZeroException.of(
                        ConfigReloadErrorCode.WATCHER_FAILED,
                        "local config watcher failed",
                        cause);
        watcherFailure.compareAndSet(null, failure);
        Instant completedAt = clock.instant();
        HotUpdateRequest request = automaticRequestFactory.create("watcher", 1, completedAt);
        ConfigReloadResult result = new ConfigReloadResult(
                "watcher",
                "watch-service",
                ConfigReloadStatus.REJECTED,
                0,
                0,
                "",
                0,
                Duration.ZERO,
                completedAt,
                ConfigReloadErrorCode.WATCHER_FAILED);
        try {
            notifyObserver(request, result);
        } catch (RuntimeException observerFailure) {
            failure.addSuppressed(observerFailure);
        }
    }

    private synchronized ConfigTable<?, ?> requireTable(final String tableName) {
        String checkedName = Objects.requireNonNull(tableName, "tableName");
        ConfigTable<?, ?> table = tables.get(checkedName);
        if (table == null) {
            throw ZeroException.of(
                    ConfigReloadErrorCode.TABLE_NOT_FOUND,
                    "config table is not registered: " + checkedName,
                    null);
        }
        return table;
    }

    private synchronized List<ConfigTable<?, ?>> tablesSnapshot() {
        return List.copyOf(tables.values());
    }

    private void requireRunning() {
        if (!running()) {
            throw ZeroException.of(
                    ConfigReloadErrorCode.INVALID_SERVICE_STATE,
                    "config reload service is not running",
                    null);
        }
    }

    private void rollBack(final List<ConfigReloadCandidate> candidates) {
        List<ConfigReloadCandidate> reversed = new ArrayList<>(candidates);
        Collections.reverse(reversed);
        reversed.forEach(ConfigReloadCandidate::rollBack);
    }

    private void closePreparedWatcher(
            final LocalConfigFileWatcher preparedWatcher,
            final Throwable originalFailure) {
        if (preparedWatcher == null) {
            return;
        }
        try {
            preparedWatcher.close();
        } catch (RuntimeException closeFailure) {
            originalFailure.addSuppressed(closeFailure);
        }
    }

    private Duration elapsed(final long startedNanos) {
        if (startedNanos == 0) {
            return Duration.ZERO;
        }
        return Duration.ofNanos(Math.max(0, System.nanoTime() - startedNanos));
    }

    private ErrorCode errorCode(final RuntimeException cause) {
        return cause instanceof ZeroException zeroException
                ? zeroException.errorCode()
                : ConfigReloadErrorCode.RELOAD_REJECTED;
    }
}
