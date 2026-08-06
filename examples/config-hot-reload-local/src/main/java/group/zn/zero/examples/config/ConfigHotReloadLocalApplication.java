package group.zn.zero.examples.config;

import group.zn.zero.core.config.MapZeroConfig;
import group.zn.zero.core.config.ZeroConfig;
import group.zn.zero.hotupdate.HotUpdateLevel;
import group.zn.zero.hotupdate.HotUpdateRequest;
import group.zn.zero.hotupdate.config.ConfigReloadResult;
import group.zn.zero.hotupdate.config.ConfigTable;
import group.zn.zero.hotupdate.config.ConfigTableDefinition;
import group.zn.zero.hotupdate.config.LocalConfigHotReloadService;
import group.zn.zero.log.InMemoryLogSink;
import group.zn.zero.starter.ZeroConfigHotReloadFactory;
import group.zn.zero.starter.ZeroRuntimeBuilder;
import group.zn.zero.starter.ZeroRuntimeComponents;
import group.zn.zero.starter.ZeroRuntimeConfigKeys;
import group.zn.zero.starter.ZeroRuntimeExecutors;
import group.zn.zero.starter.ZeroRuntimeFactory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 本地 CSV 配置初始加载、合法更新和失败保旧示例。
 *
 * @author zn
 */
public final class ConfigHotReloadLocalApplication {

    private ConfigHotReloadLocalApplication() {
    }

    /**
     * 运行可复制的本地 CSV 配置热重载闭环。
     *
     * <p>该入口只在系统临时目录创建和修改示例 CSV，不修改仓库资源或游戏持久化数据；
     * 所有配置 IO 使用 Starter 受管 remote IO executor。方法非重入，适合本地单进程演示。
     *
     * @param args 命令行参数；当前未使用，可为空数组。
     * @throws IOException 当示例 CSV 复制、修改或清理失败时抛出。
     */
    public static void main(final String[] args) throws IOException {
        DemoResult result = runDemo();
        System.out.println("config-hot-reload=ok"
                + "|initialVersion=" + result.initialVersion()
                + "|loadedVersion=" + result.loadedVersion()
                + "|rejectedVersion=" + result.rejectedVersion()
                + "|name=" + result.retainedName()
                + "|auditRecords=" + result.auditRecords());
    }

    /**
     * 执行示例并返回可测试结果。
     *
     * <p>方法创建临时 CSV、启动本地 runtime、发布 v1、手工加载合法 v2，再尝试非法重复 key；
     * 最终显式关闭 runtime 并删除临时文件。除临时目录外不修改外部数据；非线程安全。
     *
     * @return 示例结果；不可为空，不含可变集合，线程安全。
     * @throws IOException 当临时文件操作失败时抛出。
     */
    public static DemoResult runDemo() throws IOException {
        Path directory = Files.createTempDirectory("zero-config-hot-reload-");
        Path source = directory.resolve("items.csv");
        copyInitialCsv(source);
        InMemoryLogSink terminalLogSink = new InMemoryLogSink();
        ZeroRuntimeExecutors executors = ZeroRuntimeExecutors.localPrototype("config-example", 2);
        ZeroRuntimeComponents components = null;
        try {
            RuntimeAssembly assembly = assemble(source, terminalLogSink, executors);
            components = assembly.components();
            components.start();
            long initialVersion = assembly.table().snapshot().version();
            Files.writeString(source, validVersionTwo(), StandardCharsets.UTF_8);
            ConfigReloadResult loaded = assembly.service()
                    .reloadAsync("items", request("example-valid", "2"))
                    .toCompletableFuture().join();
            Files.writeString(source, invalidVersion(), StandardCharsets.UTF_8);
            ConfigReloadResult rejected = assembly.service()
                    .reloadAsync("items", request("example-invalid", "3"))
                    .toCompletableFuture().join();
            return new DemoResult(
                    initialVersion,
                    loaded.currentVersion(),
                    rejected.currentVersion(),
                    assembly.table().find(1001).orElseThrow().name(),
                    terminalLogSink.records().size());
        } finally {
            closeRuntime(components, executors);
            Files.deleteIfExists(source);
            Files.deleteIfExists(directory);
        }
    }

    private static RuntimeAssembly assemble(
            final Path source,
            final InMemoryLogSink terminalLogSink,
            final ZeroRuntimeExecutors executors) {
        ZeroConfig config = new MapZeroConfig(Map.of(
                ZeroRuntimeConfigKeys.ZERO_NAME, "config-hot-reload-local",
                ZeroRuntimeConfigKeys.CONFIG_HOT_RELOAD_ENABLED, "true",
                ZeroRuntimeConfigKeys.CONFIG_HOT_RELOAD_WATCH_ENABLED, "false",
                ZeroRuntimeConfigKeys.CONFIG_HOT_RELOAD_OPERATOR, "example-local"));
        ZeroRuntimeBuilder builder = ZeroRuntimeFactory.localBuilder(config, terminalLogSink, executors);
        LocalConfigHotReloadService service = ZeroConfigHotReloadFactory
                .configure(builder, config, builder.logAppender(), executors)
                .orElseThrow();
        ConfigTable<Integer, ItemConfig> table = service.register(ConfigTableDefinition.of(
                "items",
                source,
                "id",
                Set.of("id", "name", "price"),
                Integer::valueOf,
                row -> new ItemConfig(
                        Integer.parseInt(row.require("id")),
                        row.require("name"),
                        Integer.parseInt(row.require("price")))));
        return new RuntimeAssembly(builder.build(), service, table);
    }

    private static void copyInitialCsv(final Path target) throws IOException {
        try (InputStream input = Objects.requireNonNull(
                ConfigHotReloadLocalApplication.class.getResourceAsStream("/config/items.csv"),
                "config/items.csv")) {
            Files.copy(input, target);
        }
    }

    private static String validVersionTwo() {
        return "id,name,price\n"
                + "1001,Steel Sword,180\n"
                + "1002,Greater Healing Potion,60\n";
    }

    private static String invalidVersion() {
        return "id,name,price\n"
                + "1001,Broken Sword,1\n"
                + "1001,Duplicate Sword,2\n";
    }

    private static HotUpdateRequest request(final String traceId, final String version) {
        return new HotUpdateRequest(
                "config:items",
                HotUpdateLevel.SEAMLESS,
                version,
                "example-local",
                traceId,
                Instant.now());
    }

    private static void closeRuntime(
            final ZeroRuntimeComponents components,
            final ZeroRuntimeExecutors executors) {
        if (components == null || components.state() == group.zn.zero.core.lifecycle.LifecycleState.NEW) {
            executors.close();
        } else {
            components.stop();
        }
    }

    /**
     * 示例道具配置对象。
     *
     * @param id 道具标识。
     * @param name 道具名称。
     * @param price 道具价格。
     * @author zn
     */
    public record ItemConfig(int id, String name, int price) {
    }

    /**
     * 示例可验证结果。
     *
     * @param initialVersion 初始版本。
     * @param loadedVersion 合法重载后的版本。
     * @param rejectedVersion 非法重载拒绝后仍保留的版本。
     * @param retainedName 非法重载后继续读取到的名称。
     * @param auditRecords 产生的日志记录数。
     * @author zn
     */
    public record DemoResult(
            long initialVersion,
            long loadedVersion,
            long rejectedVersion,
            String retainedName,
            int auditRecords) {
    }

    /**
     * 示例内部 runtime 组合。
     *
     * @param components Starter 运行时组件。
     * @param service 配置热重载服务。
     * @param table typed 道具配置表。
     * @author zn
     */
    private record RuntimeAssembly(
            ZeroRuntimeComponents components,
            LocalConfigHotReloadService service,
            ConfigTable<Integer, ItemConfig> table) {
    }
}
