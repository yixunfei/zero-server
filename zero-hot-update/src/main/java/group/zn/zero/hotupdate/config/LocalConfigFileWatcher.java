package group.zn.zero.hotupdate.config;

import group.zn.zero.core.error.ZeroException;
import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 本地 CSV 文件 WatchService 适配器。
 *
 * <p>该适配器不创建线程池。阻塞循环由调用方提供的受管非内联 IO executor 承载；
 * 同一批文件事件在静默去抖窗口内合并，并按表注册顺序触发单表重载。
 *
 * @author zn
 */
final class LocalConfigFileWatcher implements AutoCloseable {

    /**
     * JDK 文件监听服务。
     */
    private final WatchService watchService;

    /**
     * watch key 与目录映射。
     */
    private final Map<WatchKey, DirectoryRegistration> registrations;

    /**
     * 文件事件静默去抖时间。
     */
    private final Duration debounce;

    /**
     * 停止等待时间。
     */
    private final Duration stopTimeout;

    /**
     * 单表重载处理器。
     */
    private final Consumer<String> reloadHandler;

    /**
     * watcher 异常处理器。
     */
    private final Consumer<Throwable> failureHandler;

    /**
     * 是否已关闭。
     */
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * 受管执行器中的 watcher 任务。
     */
    private volatile CompletableFuture<Void> task;

    private LocalConfigFileWatcher(
            final WatchService watchService,
            final Map<WatchKey, DirectoryRegistration> registrations,
            final Duration debounce,
            final Duration stopTimeout,
            final Consumer<String> reloadHandler,
            final Consumer<Throwable> failureHandler) {
        this.watchService = watchService;
        this.registrations = registrations;
        this.debounce = debounce;
        this.stopTimeout = stopTimeout;
        this.reloadHandler = reloadHandler;
        this.failureHandler = failureHandler;
    }

    /**
     * 准备 watcher 和所有父目录注册，但不启动阻塞循环。
     *
     * @param tables 配置表列表；不可为空。
     * @param options watcher 参数；不可为空。
     * @param reloadHandler 单表重载处理器；不可为空。
     * @param failureHandler 异常处理器；不可为空。
     * @return 已准备 watcher；不可为空，尚未启动任务。
     * @throws ZeroException 当 WatchService 或目录注册失败时抛出。
     */
    static LocalConfigFileWatcher prepare(
            final List<ConfigTable<?, ?>> tables,
            final ConfigHotReloadOptions options,
            final Consumer<String> reloadHandler,
            final Consumer<Throwable> failureHandler) {
        Objects.requireNonNull(tables, "tables");
        ConfigHotReloadOptions checkedOptions = Objects.requireNonNull(options, "options");
        try {
            WatchService watchService = FileSystems.getDefault().newWatchService();
            try {
                Map<WatchKey, DirectoryRegistration> registrations = registerDirectories(watchService, tables);
                return new LocalConfigFileWatcher(
                        watchService,
                        registrations,
                        checkedOptions.debounce(),
                        checkedOptions.stopTimeout(),
                        Objects.requireNonNull(reloadHandler, "reloadHandler"),
                        Objects.requireNonNull(failureHandler, "failureHandler"));
            } catch (RuntimeException | IOException ex) {
                watchService.close();
                throw ex;
            }
        } catch (IOException ex) {
            throw ZeroException.of(
                    ConfigReloadErrorCode.WATCHER_START_FAILED,
                    "failed to prepare local config watcher",
                    ex);
        }
    }

    /**
     * 在受管执行器中启动阻塞监听任务。
     *
     * @param executor 受管非内联 IO executor；不可为空，调用方负责关闭。
     * @throws ZeroException 当任务被执行器拒绝或 watcher 已启动时抛出。
     */
    synchronized void start(final Executor executor) {
        if (task != null) {
            throw ZeroException.of(
                    ConfigReloadErrorCode.INVALID_SERVICE_STATE,
                    "config watcher has already started",
                    null);
        }
        CompletableFuture<Void> currentTask = new CompletableFuture<>();
        task = currentTask;
        try {
            Objects.requireNonNull(executor, "executor").execute(() -> runLoop(currentTask));
        } catch (RejectedExecutionException ex) {
            task = null;
            throw ZeroException.of(
                    ConfigReloadErrorCode.EXECUTOR_REJECTED,
                    "config watcher task was rejected",
                    ex);
        }
    }

    private void runLoop(final CompletableFuture<Void> currentTask) {
        try {
            while (!closed.get()) {
                Set<String> tableNames = awaitBatch();
                for (String tableName : tableNames) {
                    reloadHandler.accept(tableName);
                }
            }
            currentTask.complete(null);
        } catch (ClosedWatchServiceException ex) {
            if (closed.get()) {
                currentTask.complete(null);
            } else {
                fail(currentTask, ex);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            if (closed.get()) {
                currentTask.complete(null);
            } else {
                fail(currentTask, ex);
            }
        } catch (RuntimeException | Error ex) {
            fail(currentTask, ex);
        }
    }

    private Set<String> awaitBatch() throws InterruptedException {
        LinkedHashSet<String> tableNames = new LinkedHashSet<>();
        WatchKey first = watchService.take();
        collect(first, tableNames);
        while (!closed.get()) {
            WatchKey next = watchService.poll(debounce.toNanos(), TimeUnit.NANOSECONDS);
            if (next == null) {
                break;
            }
            collect(next, tableNames);
        }
        return Collections.unmodifiableSet(tableNames);
    }

    private void collect(final WatchKey key, final Set<String> tableNames) {
        DirectoryRegistration registration = registrations.get(key);
        if (registration == null) {
            throw ZeroException.of(
                    ConfigReloadErrorCode.WATCHER_FAILED,
                    "config watcher received an unknown watch key",
                    null);
        }
        for (WatchEvent<?> event : key.pollEvents()) {
            registration.collect(event, tableNames);
        }
        if (!key.reset() && !closed.get()) {
            throw ZeroException.of(
                    ConfigReloadErrorCode.WATCHER_FAILED,
                    "config watcher directory is no longer valid",
                    null);
        }
    }

    private void fail(final CompletableFuture<Void> currentTask, final Throwable cause) {
        ZeroException failure = cause instanceof ZeroException zeroException
                ? zeroException
                : ZeroException.of(
                        ConfigReloadErrorCode.WATCHER_FAILED,
                        "local config watcher failed",
                        cause);
        try {
            failureHandler.accept(failure);
        } catch (RuntimeException observerFailure) {
            failure.addSuppressed(observerFailure);
        }
        currentTask.completeExceptionally(failure);
    }

    /**
     * 关闭 WatchService，解除阻塞循环并等待任务退出。
     *
     * @throws ZeroException 当关闭、等待超时或任务异常时抛出。
     */
    @Override
    public synchronized void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            watchService.close();
        } catch (IOException ex) {
            throw ZeroException.of(
                    ConfigReloadErrorCode.WATCHER_STOP_FAILED,
                    "failed to close config watcher",
                    ex);
        }
        awaitTask();
    }

    private void awaitTask() {
        CompletableFuture<Void> currentTask = task;
        if (currentTask == null) {
            return;
        }
        try {
            currentTask.get(stopTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw stopFailure("interrupted while stopping config watcher", ex);
        } catch (ExecutionException ex) {
            throw stopFailure("config watcher task failed", ex.getCause());
        } catch (TimeoutException ex) {
            throw stopFailure("timed out while stopping config watcher", ex);
        }
    }

    private ZeroException stopFailure(final String message, final Throwable cause) {
        return ZeroException.of(ConfigReloadErrorCode.WATCHER_STOP_FAILED, message, cause);
    }

    private static Map<WatchKey, DirectoryRegistration> registerDirectories(
            final WatchService watchService,
            final List<ConfigTable<?, ?>> tables) throws IOException {
        Map<Path, LinkedHashMap<Path, LinkedHashSet<String>>> byDirectory = new LinkedHashMap<>();
        for (ConfigTable<?, ?> table : tables) {
            Path source = table.definition().source();
            Path directory = Objects.requireNonNull(source.getParent(), "configSourceParent");
            byDirectory.computeIfAbsent(directory, ignored -> new LinkedHashMap<>())
                    .computeIfAbsent(source, ignored -> new LinkedHashSet<>())
                    .add(table.definition().tableName());
        }
        LinkedHashMap<WatchKey, DirectoryRegistration> registrations = new LinkedHashMap<>();
        for (Map.Entry<Path, LinkedHashMap<Path, LinkedHashSet<String>>> entry : byDirectory.entrySet()) {
            WatchKey key = entry.getKey().register(
                    watchService,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_DELETE);
            registrations.put(key, new DirectoryRegistration(entry.getKey(), entry.getValue()));
        }
        return Collections.unmodifiableMap(registrations);
    }

    /**
     * 单个监听目录及其文件到表名的映射。
     */
    static final class DirectoryRegistration {

        /**
         * 绝对规范目录。
         */
        private final Path directory;

        /**
         * 绝对文件路径到有序表名集合的映射。
         */
        private final Map<Path, Set<String>> tablesByFile;

        /**
         * 目录中全部表名。
         */
        private final Set<String> allTableNames;

        DirectoryRegistration(
                final Path directory,
                final Map<Path, LinkedHashSet<String>> tablesByFile) {
            this.directory = directory;
            LinkedHashMap<Path, Set<String>> copied = new LinkedHashMap<>();
            LinkedHashSet<String> allNames = new LinkedHashSet<>();
            tablesByFile.forEach((path, names) -> {
                Set<String> copiedNames = Collections.unmodifiableSet(new LinkedHashSet<>(names));
                copied.put(path, copiedNames);
                allNames.addAll(copiedNames);
            });
            this.tablesByFile = Collections.unmodifiableMap(copied);
            this.allTableNames = Collections.unmodifiableSet(allNames);
        }

        private Path directory() {
            return directory;
        }

        private Set<String> tableNames(final Path source) {
            return tablesByFile.getOrDefault(source, Set.of());
        }

        private Set<String> allTableNames() {
            return allTableNames;
        }

        /**
         * 把一个文件系统事件路由到受影响的有序表名集合。
         *
         * @param event 文件系统事件；不可为空。
         * @param tableNames 结果集合；不可为空，会追加受影响表名。
         */
        void collect(final WatchEvent<?> event, final Set<String> tableNames) {
            WatchEvent<?> checkedEvent = Objects.requireNonNull(event, "event");
            Set<String> checkedNames = Objects.requireNonNull(tableNames, "tableNames");
            if (checkedEvent.kind() == StandardWatchEventKinds.OVERFLOW) {
                checkedNames.addAll(allTableNames());
            } else if (checkedEvent.context() instanceof Path relativePath) {
                Path changed = directory().resolve(relativePath).toAbsolutePath().normalize();
                checkedNames.addAll(tableNames(changed));
            }
        }
    }
}
