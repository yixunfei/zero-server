package group.zn.zero.examples.recipes.production;

import group.zn.zero.core.config.MapZeroConfig;
import group.zn.zero.data.redis.RedisDriverSettings;
import group.zn.zero.runtime.api.RuntimeState;
import group.zn.zero.runtime.bootstrap.RuntimeBasics;
import group.zn.zero.runtime.bootstrap.ZeroRuntimeExecutors;
import group.zn.zero.runtime.production.ProductionAdapterException;
import group.zn.zero.runtime.production.ProductionAdapterErrorCode;
import group.zn.zero.runtime.production.ProductionAdapterNames;
import group.zn.zero.runtime.production.ProductionAssembly;
import group.zn.zero.runtime.production.ZeroProductionAdapterState;
import group.zn.zero.runtime.production.ZeroProductionRuntimeConfigKeys;
import group.zn.zero.starter.production.ZeroProductionRuntimeFactory;
import java.util.List;
import java.util.Map;

/**
 * 生产接入前检查：缺失配置诊断、安全失败、关闭生命周期。
 * 本例隔离进程配置；仅诊断启用的 Redis，不创建真实 Adapter 或连接中间件。
 * @author zn
 */
public final class ProductionExample {
    private ProductionExample() { }

    /** @param args 未使用；打印键名及状态，不打印连接串或配置值。 */
    public static void main(final String[] args) {
        System.out.println("production=" + runFlow());
    }

    /**
     * 先诊断无法 build 的缓存配置，再启动不含外部 Adapter 的基础 production runtime。
     * 每次调用隔离；diagnose 阶段显式执行器也由本方法兜底关闭。
     * @return 非空不可变摘要；缺失键有序且不可变，可跨线程读取。
     * @throws IllegalStateException 不完整配置意外允许创建。
     * @throws RuntimeException 非预期生命周期失败。
     */
    public static Result runFlow() {
        String sentinel = "fixture-secret-not-a-real-password";
        var incomplete = new MapZeroConfig(Map.of(
                ZeroProductionRuntimeConfigKeys.ADAPTER_CACHE_REDIS_ENABLED, "true",
                RedisDriverSettings.PROPERTY_REDIS_URI, "redis://:" + sentinel + "@127.0.0.1:1/0"));
        List<String> missing;
        ZeroProductionAdapterState state;
        boolean redacted;
        String failureCode;
        try (var executors = ZeroRuntimeExecutors.remoteIo("recipe-diagnosis")) {
            var builder = ZeroProductionRuntimeFactory.builder("production", incomplete, record -> { }, executors)
                    .configSourceLookups(key -> null, key -> null);
            var report = builder.diagnose();
            var status = report.adapterStatus(ProductionAdapterNames.ADAPTER_REDIS_CACHE).orElseThrow();
            missing = status.missingConfigKeys();
            state = status.state();
            redacted = !report.containsFragment(sentinel) && !report.containsFragment("redis://");
            failureCode = missingConfigurationCode(builder::build);
        }
        // 不安装外部模块的轻量组合根，只拥有配置和框架受管 IO 执行器。
        var runtime = ProductionAssembly.builder("production", new MapZeroConfig(Map.of()))
                .configSourceLookups(key -> null, key -> null).build();
        boolean started;
        boolean managedIo;
        try (runtime) {
            runtime.start();
            started = runtime.running();
            managedIo = !runtime.require(RuntimeBasics.EXECUTORS).remoteIoMayInline();
        }
        return new Result(state, missing, failureCode, redacted, started, runtime.runtimeState() == RuntimeState.CLOSED, managedIo);
    }

    private static String missingConfigurationCode(
            final java.util.function.Supplier<group.zn.zero.runtime.production.ZeroProductionRuntime> create) {
        try (var unexpected = create.get()) {
            throw new IllegalStateException("不完整缓存配置不应允许 build");
        } catch (ProductionAdapterException failure) {
            if (failure.errorCode() != ProductionAdapterErrorCode.CONFIG_MISSING) {
                throw failure;
            }
            return failure.errorCode().code();
        }
    }

    /**
     * 安全诊断摘要；线程安全，不代表外部 Adapter 健康验收。
     * @param redisState Redis 配置诊断状态。
     * @param missingKeys 有序不可变缺失键列表，可为空。
     * @param failureCode build 失败的稳定错误码。
     * @param redacted 报告是否未包含 fixture 密码和连接串。
     * @param started 基础 runtime 是否启动。
     * @param stopped 是否关闭。
     * @param managedIo 是否使用框架受管远程 IO。
     * @author zn
     */
    public record Result(ZeroProductionAdapterState redisState, List<String> missingKeys, String failureCode,
                         boolean redacted, boolean started, boolean stopped, boolean managedIo) {
        /** 复制缺失键列表；null 列表抛出 NullPointerException。 */
        public Result { missingKeys = List.copyOf(missingKeys); }
    }
}
