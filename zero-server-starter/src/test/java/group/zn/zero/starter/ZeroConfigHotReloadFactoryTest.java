package group.zn.zero.starter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.core.config.MapZeroConfig;
import group.zn.zero.core.config.ZeroConfig;
import group.zn.zero.core.error.ZeroException;
import group.zn.zero.hotupdate.HotUpdateLevel;
import group.zn.zero.hotupdate.HotUpdateRequest;
import group.zn.zero.hotupdate.config.ConfigReloadErrorCode;
import group.zn.zero.hotupdate.config.ConfigReloadResult;
import group.zn.zero.hotupdate.config.ConfigReloadStatus;
import group.zn.zero.hotupdate.config.ConfigTable;
import group.zn.zero.hotupdate.config.ConfigTableDefinition;
import group.zn.zero.hotupdate.config.LocalConfigHotReloadService;
import group.zn.zero.log.InMemoryLogSink;
import group.zn.zero.log.LogType;
import group.zn.zero.log.ZeroLogRecord;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Starter CSV 配置热重载 opt-in、线程边界和审计 focused tests。
 *
 * @author zn
 */
class ZeroConfigHotReloadFactoryTest {

    /**
     * JUnit 隔离的配置文件目录。
     */
    @TempDir
    private Path tempDirectory;

    /**
     * 验证默认关闭时工厂不修改 builder，且不要求创建受管线程。
     */
    @Test
    void shouldRemainDisabledByDefault() {
        ZeroConfig config = new MapZeroConfig(Map.of());
        InMemoryLogSink logSink = new InMemoryLogSink();
        ZeroRuntimeExecutors executors = ZeroRuntimeExecutors.direct();
        ZeroRuntimeBuilder builder = ZeroRuntimeFactory.localBuilder(config, logSink, executors);

        Optional<LocalConfigHotReloadService> service =
                ZeroConfigHotReloadFactory.configure(builder, config, builder.logAppender(), executors);

        assertTrue(service.isEmpty());
        ZeroRuntimeComponents components = builder.build();
        assertEquals(1, components.lifecycleComponents().size());
        components.start();
        components.stop();
    }

    /**
     * CFHR-10：验证启用后拒绝可能内联的 remote IO executor。
     */
    @Test
    void shouldRejectInlineRemoteIoExecutor() {
        ZeroConfig config = config(Map.of(ZeroRuntimeConfigKeys.CONFIG_HOT_RELOAD_ENABLED, "true"));
        InMemoryLogSink logSink = new InMemoryLogSink();
        ZeroRuntimeExecutors executors = ZeroRuntimeExecutors.direct();
        ZeroRuntimeBuilder builder = ZeroRuntimeFactory.localBuilder(config, logSink, executors);

        ZeroException failure = assertThrows(
                ZeroException.class,
                () -> ZeroConfigHotReloadFactory.configure(
                        builder,
                        config,
                        builder.logAppender(),
                        executors));

        assertSame(ConfigReloadErrorCode.INVALID_OPTIONS, failure.errorCode());
    }

    /**
     * 验证非法布尔值和非正超时不会被静默接受。
     */
    @Test
    void shouldRejectInvalidStarterOptions() {
        ZeroConfig invalidBoolean = config(Map.of(
                ZeroRuntimeConfigKeys.CONFIG_HOT_RELOAD_ENABLED, "yes"));
        ZeroException booleanFailure = assertThrows(
                ZeroException.class,
                () -> ZeroConfigHotReloadFactory.enabled(invalidBoolean));
        assertSame(ConfigReloadErrorCode.INVALID_OPTIONS, booleanFailure.errorCode());

        ZeroConfig invalidTimeout = config(Map.of(
                ZeroRuntimeConfigKeys.CONFIG_HOT_RELOAD_ENABLED, "true",
                ZeroRuntimeConfigKeys.CONFIG_HOT_RELOAD_STOP_TIMEOUT_MILLIS, "0"));
        InMemoryLogSink logSink = new InMemoryLogSink();
        ZeroRuntimeExecutors executors = ZeroRuntimeExecutors.localPrototype("invalid-config", 2);
        try {
            ZeroRuntimeBuilder builder = ZeroRuntimeFactory.localBuilder(invalidTimeout, logSink, executors);
            ZeroException timeoutFailure = assertThrows(
                    ZeroException.class,
                    () -> ZeroConfigHotReloadFactory.configure(
                            builder,
                            invalidTimeout,
                            builder.logAppender(),
                            executors));
            assertSame(ConfigReloadErrorCode.INVALID_OPTIONS, timeoutFailure.errorCode());
        } finally {
            executors.close();
        }
    }

    /**
     * CFHR-10～11：验证 CSV IO 位于受管 remote IO 线程，watcher 可关闭且日志不泄漏内容或完整路径。
     *
     * @throws IOException 当测试 CSV 写入失败时抛出。
     */
    @Test
    void shouldUseManagedRemoteIoAndWriteSafeAuditRecords() throws IOException {
        Path source = tempDirectory.resolve("items.csv");
        Files.writeString(source, "id,name\n1,top-secret-row-value\n", StandardCharsets.UTF_8);
        ZeroConfig config = config(Map.of(
                ZeroRuntimeConfigKeys.CONFIG_HOT_RELOAD_ENABLED, "true",
                ZeroRuntimeConfigKeys.CONFIG_HOT_RELOAD_WATCH_ENABLED, "true",
                ZeroRuntimeConfigKeys.CONFIG_HOT_RELOAD_DEBOUNCE_MILLIS, "100",
                ZeroRuntimeConfigKeys.CONFIG_HOT_RELOAD_OPERATOR, "starter-local"));
        InMemoryLogSink logSink = new InMemoryLogSink();
        ZeroRuntimeExecutors executors = ZeroRuntimeExecutors.localPrototype("config-factory", 2);
        ZeroRuntimeBuilder builder = ZeroRuntimeFactory.localBuilder(config, logSink, executors);
        LocalConfigHotReloadService service = ZeroConfigHotReloadFactory
                .configure(builder, config, builder.logAppender(), executors)
                .orElseThrow();
        AtomicReference<String> decoderThread = new AtomicReference<>();
        ConfigTable<Integer, StarterItem> table = service.register(ConfigTableDefinition.of(
                "items",
                source,
                "id",
                Set.of("id", "name"),
                Integer::valueOf,
                row -> {
                    decoderThread.set(Thread.currentThread().getName());
                    return new StarterItem(Integer.parseInt(row.require("id")), row.require("name"));
                }));
        ZeroRuntimeComponents components = builder.build();

        components.start();
        try {
            assertTrue(decoderThread.get().startsWith("config-factory-remote-io-"));
            assertEquals("top-secret-row-value", table.find(1).orElseThrow().name());
            Files.writeString(source, "id,name\n1,duplicate\n1,top-secret-row-value\n", StandardCharsets.UTF_8);
            ConfigReloadResult rejected = service.reloadAsync("items", request()).toCompletableFuture().join();
            assertSame(ConfigReloadStatus.REJECTED, rejected.status());
            assertSafeAuditRecords(logSink.records(), source);
            assertFalse(service.watcherFailure().isPresent());
        } finally {
            components.stop();
        }
    }

    private static void assertSafeAuditRecords(
            final List<ZeroLogRecord> records,
            final Path source) {
        assertTrue(records.stream().anyMatch(record -> record.logType() == LogType.AUDIT));
        assertTrue(records.stream().anyMatch(record -> record.logType() == LogType.ERROR
                && record.errorCode() == ConfigReloadErrorCode.DUPLICATE_KEY));
        assertTrue(records.stream().allMatch(record -> !record.traceId().isBlank()));
        String rendered = records.toString();
        assertFalse(rendered.contains(source.toAbsolutePath().toString()));
        assertFalse(rendered.contains("top-secret-row-value"));
        assertTrue(records.stream().allMatch(record -> "items.csv".equals(record.fields().get("source"))));
        assertTrue(records.stream().allMatch(record -> "[REDACTED]".equals(record.fields().get("operator"))));
    }

    private static HotUpdateRequest request() {
        return new HotUpdateRequest(
                "config:items",
                HotUpdateLevel.SEAMLESS,
                "2",
                "starter-local",
                "starter-trace",
                Instant.now());
    }

    private static ZeroConfig config(final Map<String, String> values) {
        return new MapZeroConfig(values);
    }

    /**
     * Starter 测试用 typed 配置对象。
     *
     * @param id 配置标识。
     * @param name 配置名称。
     * @author zn
     */
    private record StarterItem(int id, String name) {
    }
}
