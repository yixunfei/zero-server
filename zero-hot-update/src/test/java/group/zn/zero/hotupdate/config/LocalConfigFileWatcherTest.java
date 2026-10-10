package group.zn.zero.hotupdate.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.core.error.ZeroException;
import group.zn.zero.core.lifecycle.LifecycleState;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 本地 CSV WatchService 路由、去抖与关闭 focused tests。
 *
 * @author zn
 */
class LocalConfigFileWatcherTest {

    /**
     * JUnit 隔离的文件监听目录。
     */
    @TempDir
    private Path tempDirectory;

    /** 实际文件变更触发 watcher 主失败，观察者异常只能追加，不能覆盖主错误。 */
    @Test void watcherPrimaryFailureRetainsObserverFailureAsSuppressed() throws Exception {
        Path source = tempDirectory.resolve("observer-failure.csv");
        write(source, "id,name\n1,initial\n");
        var primary = new IllegalStateException("request-factory");
        var local = ConfigReloadRequestFactory.local("watcher-test");
        var observed = new CompletableFuture<Void>();
        try (ExecutorService worker = Executors.newSingleThreadExecutor()) {
            var service = new LocalConfigHotReloadService(worker,
                    (table, version, time) -> {
                        if (version > 1) throw primary;
                        return local.create(table, version, time);
                    }, (request, result) -> {
                        if (result.errorCode() == ConfigReloadErrorCode.WATCHER_FAILED) {
                            observed.complete(null);
                            throw new IllegalArgumentException("observer");
                        }
                    }, new ConfigHotReloadOptions(true, Duration.ofMillis(1), Duration.ofSeconds(1)));
            service.register(definition(source));
            service.start();
            try {
                write(source, "id,name\n1,updated\n");
                observed.get(5, TimeUnit.SECONDS);
                assertFalse(service.healthy());
            } finally {
                var stopFailure = assertThrows(ZeroException.class, service::stop);
                assertEquals(ConfigReloadErrorCode.WATCHER_STOP_FAILED, stopFailure.errorCode());
            }
            var failure = service.watcherFailure().orElseThrow();
            assertSame(primary, failure.getCause());
            assertEquals(1, failure.getSuppressed().length);
            assertEquals(ConfigReloadErrorCode.OBSERVER_FAILED,
                    ((ZeroException) failure.getSuppressed()[0]).errorCode());
        }
    }

    /**
     * CFHR-09：验证普通文件事件只命中对应表，OVERFLOW 命中目录内全部受管表。
     */
    @Test
    void shouldRouteFileAndOverflowEventsInRegistrationOrder() {
        Path directory = tempDirectory.toAbsolutePath().normalize();
        LinkedHashMap<Path, LinkedHashSet<String>> tablesByFile = new LinkedHashMap<>();
        tablesByFile.put(directory.resolve("items.csv"), new LinkedHashSet<>(List.of("items")));
        tablesByFile.put(directory.resolve("levels.csv"), new LinkedHashSet<>(List.of("levels", "level-index")));
        LocalConfigFileWatcher.DirectoryRegistration registration =
                new LocalConfigFileWatcher.DirectoryRegistration(directory, tablesByFile);
        LinkedHashSet<String> names = new LinkedHashSet<>();

        registration.collect(event(StandardWatchEventKinds.ENTRY_MODIFY, Path.of("levels.csv")), names);
        assertEquals(List.of("levels", "level-index"), new ArrayList<>(names));

        names.clear();
        registration.collect(event(StandardWatchEventKinds.OVERFLOW, null), names);
        assertEquals(List.of("items", "levels", "level-index"), new ArrayList<>(names));
    }

    /**
     * CFHR-09～10：验证快速变更被去抖为最终表版本，stop 能关闭 WatchService 并解除阻塞。
     *
     * @throws Exception 当测试文件或等待流程失败时抛出。
     */
    @Test
    void shouldDebounceRapidChangesAndUnblockWatcherOnStop() throws Exception {
        Path source = tempDirectory.resolve("items.csv");
        write(source, "id,name\n1,initial\n");
        List<ConfigReloadResult> results = Collections.synchronizedList(new ArrayList<>());

        try (ExecutorService remoteIo = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("config-watcher-test-", 0).factory())) {
            LocalConfigHotReloadService service = new LocalConfigHotReloadService(
                    remoteIo,
                    ConfigReloadRequestFactory.local("watcher-test"),
                    (request, result) -> results.add(result),
                    new ConfigHotReloadOptions(true, Duration.ofMillis(250), Duration.ofSeconds(2)));
            service.authorizer((name, request, automatic) -> automatic);
            ConfigTable<Integer, WatchedItem> table = service.register(definition(source));
            service.start();

            write(source, "id,name\n1,intermediate\n");
            write(source, "id,name\n1,final\n");
            awaitName(table, "final", Duration.ofSeconds(5));

            long runtimeLoads = results.stream()
                    .filter(result -> result.previousVersion() > 0)
                    .filter(result -> result.status() == ConfigReloadStatus.LOADED)
                    .count();
            assertEquals(1, runtimeLoads);
            assertEquals(2, table.snapshot().version());
            service.stop();
            assertEquals(LifecycleState.STOPPED, service.state());
            assertFalse(service.watcherFailure().isPresent());
        }
    }

    /** 文件重载回调可以关闭自己的 watcher，不能等待当前循环自己退出。 */
    @Test
    void shouldAllowCloseFromReloadCallback() throws Exception {
        Path source = tempDirectory.resolve("self-close.csv");
        write(source, "id,name\n1,initial\n");
        CompletableFuture<Void> closed = new CompletableFuture<>();
        LocalConfigFileWatcher[] holder = new LocalConfigFileWatcher[1];
        holder[0] = LocalConfigFileWatcher.prepare(
                List.of(new ConfigTable<>(definition(source))),
                new ConfigHotReloadOptions(true, Duration.ofMillis(1), Duration.ofMillis(100)),
                ignored -> {
                    try {
                        holder[0].close();
                        closed.complete(null);
                    } catch (Throwable failure) {
                        closed.completeExceptionally(failure);
                    }
                }, closed::completeExceptionally);
        try (ExecutorService worker = Executors.newSingleThreadExecutor(); var watcher = holder[0]) {
            watcher.start(worker);
            write(source, "id,name\n1,updated\n");
            closed.get(5, TimeUnit.SECONDS);
        }
    }

    /** 故障回调重抛原异常时，监听任务仍须完成，关闭不能误报等待超时。 */
    @Test
    void rethrownFailureStillCompletesWatcherTask() throws Exception {
        Path source = tempDirectory.resolve("failure.csv");
        write(source, "id,name\n1,initial\n");
        AtomicReference<CompletableFuture<Void>> loop = new AtomicReference<>();
        try (ExecutorService worker = Executors.newSingleThreadExecutor()) {
            LocalConfigFileWatcher watcher = LocalConfigFileWatcher.prepare(
                    List.of(new ConfigTable<>(definition(source))),
                    new ConfigHotReloadOptions(true, Duration.ofMillis(1), Duration.ofMillis(100)),
                    ignored -> { throw new IllegalStateException("reload failed"); },
                    failure -> { throw (ZeroException) failure; });
            watcher.start(command -> loop.set(CompletableFuture.runAsync(command, worker)));
            try {
                write(source, "id,name\n1,updated\n");
                loop.get().get(5, TimeUnit.SECONDS);
            } finally {
                ZeroException failure = assertThrows(ZeroException.class, watcher::close);
                assertEquals("config watcher task failed", failure.getMessage());
            }
        }
    }

    /** 执行器在提交前失败时不能保留永不执行的任务，随后关闭不应等待超时。 */
    @Test
    void executorErrorDoesNotLeaveUnfinishedWatcherTask() throws Exception {
        Path source = tempDirectory.resolve("submission-error.csv");
        write(source, "id,name\n1,initial\n");
        AssertionError cause = new AssertionError("executor unavailable");
        try (LocalConfigFileWatcher watcher = LocalConfigFileWatcher.prepare(
                List.of(new ConfigTable<>(definition(source))),
                new ConfigHotReloadOptions(true, Duration.ofMillis(1), Duration.ofMillis(100)),
                ignored -> { }, ignored -> { })) {
            ZeroException failure = assertThrows(ZeroException.class,
                    () -> watcher.start(command -> { throw cause; }));
            assertSame(cause, failure.getCause());
            assertEquals(ConfigReloadErrorCode.EXECUTOR_REJECTED, failure.errorCode());
        }
    }

    private static ConfigTableDefinition<Integer, WatchedItem> definition(final Path source) {
        return ConfigTableDefinition.of(
                "items",
                source,
                "id",
                Set.of("id", "name"),
                Integer::valueOf,
                row -> new WatchedItem(Integer.parseInt(row.require("id")), row.require("name")));
    }

    private static void awaitName(
            final ConfigTable<Integer, WatchedItem> table,
            final String expected,
            final Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (expected.equals(table.find(1).orElseThrow().name())) {
                return;
            }
            Thread.sleep(20);
        }
        assertEquals(expected, table.find(1).orElseThrow().name());
    }

    private static void write(final Path source, final String csv) throws IOException {
        Files.writeString(source, csv, StandardCharsets.UTF_8);
    }

    private static <T> WatchEvent<T> event(final WatchEvent.Kind<T> kind, final T context) {
        return new WatchEvent<>() {
            @Override
            public Kind<T> kind() {
                return kind;
            }

            @Override
            public int count() {
                return 1;
            }

            @Override
            public T context() {
                return context;
            }
        };
    }

    /**
     * watcher 测试用 typed 配置。
     *
     * @param id 配置标识。
     * @param name 配置名称。
     * @author zn
     */
    private record WatchedItem(int id, String name) {
    }
}
