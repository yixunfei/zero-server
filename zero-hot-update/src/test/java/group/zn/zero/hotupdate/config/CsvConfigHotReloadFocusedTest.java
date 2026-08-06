package group.zn.zero.hotupdate.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.core.error.ZeroException;
import group.zn.zero.hotupdate.HotUpdateLevel;
import group.zn.zero.hotupdate.HotUpdateRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * CSV 配置初始加载、校验、原子替换和并发读取 focused tests。
 *
 * @author zn
 */
class CsvConfigHotReloadFocusedTest {

    /**
     * JUnit 隔离的配置文件目录。
     */
    @TempDir
    private Path tempDirectory;

    /**
     * CFHR-01：验证 UTF-8 BOM、RFC 4180 逗号与引号内换行。
     *
     * @throws IOException 当测试文件写入失败时抛出。
     */
    @Test
    void shouldParseBomAndRfc4180QuotedContent() throws IOException {
        Path source = tempDirectory.resolve("items.csv");
        byte[] csv = ("\uFEFFid,name,price\r\n"
                + "1,\"Sword, Prime\",10\r\n"
                + "2,\"Two\r\nLines\",20\r\n").getBytes(StandardCharsets.UTF_8);
        Files.write(source, csv);
        LocalConfigHotReloadService service = service(ConfigReloadObserver.noOp());
        ConfigTable<Integer, ItemConfig> table = service.register(definition(source));

        service.start();

        assertEquals("Sword, Prime", table.find(1).orElseThrow().name());
        assertEquals("Two\r\nLines", table.find(2).orElseThrow().name());
        assertEquals(List.of(1, 2), new ArrayList<>(table.snapshot().values().keySet()));
        service.stop();
    }

    /**
     * CFHR-02：验证缺列、列数异常、空 key 和非法 CSV 均整表拒绝。
     *
     * @throws IOException 当测试文件写入失败时抛出。
     */
    @Test
    void shouldRejectStructuralCsvFailures() throws IOException {
        assertInitialFailure("id,name\n1,sword\n", ConfigReloadErrorCode.REQUIRED_COLUMN_MISSING);
        assertInitialFailure("id,name,price\n1,sword,10,extra\n", ConfigReloadErrorCode.CSV_PARSE_FAILED);
        assertInitialFailure("id,name,price\n,sword,10\n", ConfigReloadErrorCode.EMPTY_KEY);
        assertInitialFailure("id,name,price\n1,\"unterminated\n", ConfigReloadErrorCode.CSV_PARSE_FAILED);
    }

    /**
     * CFHR-03：验证重复 key、decoder 和业务 validator 失败均拒绝整表。
     *
     * @throws IOException 当测试文件写入失败时抛出。
     */
    @Test
    void shouldRejectDecodeAndValidationFailures() throws IOException {
        assertInitialFailure(
                "id,name,price\n1,sword,10\n1,shield,20\n",
                ConfigReloadErrorCode.DUPLICATE_KEY);
        assertInitialFailure(
                "id,name,price\nnot-an-int,sword,10\n",
                ConfigReloadErrorCode.KEY_DECODE_FAILED);
        assertInitialFailure(
                "id,name,price\n1,sword,not-an-int\n",
                ConfigReloadErrorCode.ROW_DECODE_FAILED);

        Path source = write("validation.csv", "id,name,price\n1,sword,-1\n");
        LocalConfigHotReloadService service = service(ConfigReloadObserver.noOp());
        ConfigTable<Integer, ItemConfig> table = service.register(new ConfigTableDefinition<>(
                "items",
                source,
                "id",
                Set.of("id", "name", "price"),
                Integer::valueOf,
                CsvConfigHotReloadFocusedTest::decodeItem,
                values -> List.of(new ConfigValidationIssue("price", "price must be positive"))));

        ZeroException failure = assertThrows(ZeroException.class, service::start);
        assertSame(ConfigReloadErrorCode.VALIDATION_FAILED, failure.errorCode());
        assertFalse(table.loaded());
    }

    /**
     * CFHR-04：验证初始阶段任一候选失败时不发布任何已注册表。
     *
     * @throws IOException 当测试文件写入失败时抛出。
     */
    @Test
    void shouldValidateAllInitialTablesBeforePublishingAnySnapshot() throws IOException {
        Path valid = write("valid.csv", "id,name,price\n1,sword,10\n");
        Path invalid = write("invalid.csv", "id,name\n2,shield\n");
        LocalConfigHotReloadService service = service(ConfigReloadObserver.noOp());
        ConfigTable<Integer, ItemConfig> validTable = service.register(definition("valid-items", valid));
        ConfigTable<Integer, ItemConfig> invalidTable = service.register(definition("invalid-items", invalid));

        ZeroException failure = assertThrows(ZeroException.class, service::start);

        assertSame(ConfigReloadErrorCode.REQUIRED_COLUMN_MISSING, failure.errorCode());
        assertFalse(validTable.loaded());
        assertFalse(invalidTable.loaded());
    }

    /**
     * 验证初始成功 observer 收到 LOADED 时对应快照已经发布可读。
     *
     * @throws IOException 当测试文件写入失败时抛出。
     */
    @Test
    void shouldPublishInitialSnapshotBeforeSuccessfulObservation() throws IOException {
        Path source = write("observer.csv", "id,name,price\n1,sword,10\n");
        AtomicBoolean readableDuringObservation = new AtomicBoolean();
        AtomicReference<ConfigTable<Integer, ItemConfig>> tableReference = new AtomicReference<>();
        LocalConfigHotReloadService service = service((request, result) -> {
            if (result.status() == ConfigReloadStatus.LOADED) {
                readableDuringObservation.set(tableReference.get().snapshot().version() == 1);
            }
        });
        ConfigTable<Integer, ItemConfig> table = service.register(definition(source));
        tableReference.set(table);

        service.start();

        assertTrue(readableDuringObservation.get());
        service.stop();
    }

    /**
     * CFHR-05～07：验证有序不可变快照、合法替换、失败保旧和摘要 no-op。
     *
     * @throws IOException 当测试文件写入失败时抛出。
     */
    @Test
    void shouldAtomicallyReloadKeepOldSnapshotAndSkipSameChecksum() throws IOException {
        Path source = write("items.csv", "id,name,price\n1,sword,10\n2,shield,20\n");
        List<ConfigReloadResult> observed = Collections.synchronizedList(new ArrayList<>());
        LocalConfigHotReloadService service = service((request, result) -> observed.add(result));
        ConfigTable<Integer, ItemConfig> table = service.register(definition(source));
        service.start();
        ConfigTableSnapshot<Integer, ItemConfig> initial = table.snapshot();
        assertThrows(UnsupportedOperationException.class,
                () -> initial.values().put(3, new ItemConfig(3, "bow", 30)));

        String versionTwo = "id,name,price\n2,tower-shield,25\n1,steel-sword,15\n";
        Files.writeString(source, versionTwo, StandardCharsets.UTF_8);
        ConfigReloadResult loaded = service.reloadAsync("items", request("manual-2")).toCompletableFuture().join();
        assertSame(ConfigReloadStatus.LOADED, loaded.status());
        assertEquals(2, table.snapshot().version());
        assertEquals(List.of(2, 1), new ArrayList<>(table.snapshot().values().keySet()));

        Files.writeString(source, "id,name,price\n2,broken,25\n2,duplicate,30\n", StandardCharsets.UTF_8);
        ConfigReloadResult rejected = service.reloadAsync("items", request("manual-bad"))
                .toCompletableFuture().join();
        assertSame(ConfigReloadStatus.REJECTED, rejected.status());
        assertSame(ConfigReloadErrorCode.DUPLICATE_KEY, rejected.errorCode());
        assertEquals("tower-shield", table.find(2).orElseThrow().name());
        assertEquals(2, table.snapshot().version());

        Files.writeString(source, versionTwo, StandardCharsets.UTF_8);
        ConfigTableSnapshot<Integer, ItemConfig> beforeNoChange = table.snapshot();
        ConfigReloadResult noChange = service.reloadAsync("items", request("manual-no-change"))
                .toCompletableFuture().join();
        assertSame(ConfigReloadStatus.NO_CHANGE, noChange.status());
        assertSame(beforeNoChange, table.snapshot());
        assertEquals(2, table.snapshot().version());
        assertEquals(4, observed.size());
        service.stop();
    }

    /**
     * CFHR-08：验证并发读取只能看到完整旧快照或完整新快照。
     *
     * @throws Exception 当测试 IO 或并发任务失败时抛出。
     */
    @Test
    void shouldExposeOnlyCompleteSnapshotsToConcurrentReaders() throws Exception {
        Path source = write("large.csv", generationCsv("old", 400));
        LocalConfigHotReloadService service = service(ConfigReloadObserver.noOp());
        ConfigTable<Integer, ItemConfig> table = service.register(definition(source));
        service.start();
        AtomicBoolean reading = new AtomicBoolean(true);
        AtomicInteger observations = new AtomicInteger();
        CountDownLatch ready = new CountDownLatch(4);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<Void>> readers = new ArrayList<>();
            for (int index = 0; index < 4; index++) {
                readers.add(CompletableFuture.runAsync(
                        () -> readCompleteSnapshots(table, reading, observations, ready),
                        executor));
            }
            ready.await();
            Files.writeString(source, generationCsv("new", 400), StandardCharsets.UTF_8);
            ConfigReloadResult result = service.reloadAsync("items", request("concurrent"))
                    .toCompletableFuture().join();
            assertSame(ConfigReloadStatus.LOADED, result.status());
            reading.set(false);
            CompletableFuture.allOf(readers.toArray(CompletableFuture[]::new)).join();
        }

        assertTrue(observations.get() > 0);
        assertEquals("new-400", table.find(400).orElseThrow().name());
        service.stop();
    }

    private void assertInitialFailure(
            final String csv,
            final ConfigReloadErrorCode expectedCode) throws IOException {
        Path source = write("failure-" + expectedCode.name() + ".csv", csv);
        LocalConfigHotReloadService service = service(ConfigReloadObserver.noOp());
        ConfigTable<Integer, ItemConfig> table = service.register(definition(source));
        ZeroException failure = assertThrows(ZeroException.class, service::start);
        assertSame(expectedCode, failure.errorCode());
        assertFalse(table.loaded());
    }

    private Path write(final String fileName, final String content) throws IOException {
        Path source = tempDirectory.resolve(fileName);
        Files.writeString(source, content, StandardCharsets.UTF_8);
        return source;
    }

    private static LocalConfigHotReloadService service(final ConfigReloadObserver observer) {
        return new LocalConfigHotReloadService(
                Runnable::run,
                ConfigReloadRequestFactory.local("focused-test"),
                observer,
                ConfigHotReloadOptions.manualOnly());
    }

    private static ConfigTableDefinition<Integer, ItemConfig> definition(final Path source) {
        return definition("items", source);
    }

    private static ConfigTableDefinition<Integer, ItemConfig> definition(
            final String tableName,
            final Path source) {
        return ConfigTableDefinition.of(
                tableName,
                source,
                "id",
                new LinkedHashSet<>(List.of("id", "name", "price")),
                Integer::valueOf,
                CsvConfigHotReloadFocusedTest::decodeItem);
    }

    private static ItemConfig decodeItem(final CsvRow row) {
        return new ItemConfig(
                Integer.parseInt(row.require("id")),
                row.require("name"),
                Integer.parseInt(row.require("price")));
    }

    private static HotUpdateRequest request(final String traceId) {
        return new HotUpdateRequest(
                "config:items",
                HotUpdateLevel.SEAMLESS,
                "next",
                "focused-test",
                traceId,
                Instant.now());
    }

    private static String generationCsv(final String generation, final int rows) {
        StringBuilder csv = new StringBuilder("id,name,price\n");
        for (int id = 1; id <= rows; id++) {
            csv.append(id).append(',').append(generation).append('-').append(id).append(',').append(id).append('\n');
        }
        return csv.toString();
    }

    private static void readCompleteSnapshots(
            final ConfigTable<Integer, ItemConfig> table,
            final AtomicBoolean reading,
            final AtomicInteger observations,
            final CountDownLatch ready) {
        ready.countDown();
        while (reading.get()) {
            ConfigTableSnapshot<Integer, ItemConfig> snapshot = table.snapshot();
            String expectedPrefix = snapshot.version() == 1 ? "old-" : "new-";
            for (Map.Entry<Integer, ItemConfig> entry : snapshot.values().entrySet()) {
                if (!entry.getValue().name().equals(expectedPrefix + entry.getKey())) {
                    throw new AssertionError("mixed config snapshot observed at version " + snapshot.version());
                }
            }
            observations.incrementAndGet();
        }
    }

    /**
     * 测试用 typed 游戏道具配置。
     *
     * @param id 道具标识。
     * @param name 道具名称。
     * @param price 道具价格。
     * @author zn
     */
    private record ItemConfig(int id, String name, int price) {
    }
}
