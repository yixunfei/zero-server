package group.zn.zero.examples.recipes.basics;

import group.zn.zero.core.config.MapZeroConfig;
import group.zn.zero.runtime.api.RuntimeState;
import group.zn.zero.runtime.assembly.RuntimeComposition;
import group.zn.zero.runtime.assembly.RuntimeProfile;
import group.zn.zero.runtime.bootstrap.RuntimeBasics;
import group.zn.zero.runtime.bootstrap.ZeroRuntimeExecutors;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 最小接入：配置 → 诊断 → 启动 → 受管任务 → 关闭。不安装网络或中间件。
 * 每次运行独占 runtime；只能在命令行等边缘线程等待结果。
 * @author zn
 */
public final class RuntimeBasicsExample {
    private RuntimeBasicsExample() { }

    /**
     * 执行并打印安全摘要；每次调用隔离，可并发运行。
     * @param args 未使用。
     * @throws Exception 任务失败、等待超时或调用线程被中断。
     */
    public static void main(final String[] args) throws Exception {
        System.out.println("runtime-basics=" + runFlow());
    }

    /**
     * 演示配置默认值及真实受管逻辑线程；关闭时释放本次全部执行器。
     * @return 不可变、非空结果，可跨线程读取；不保留 runtime 引用。
     * @throws Exception 任务失败、五秒内未完成或调用线程被中断。
     */
    public static Result runFlow() throws Exception {
        var config = new MapZeroConfig(Map.of("game.region", "local-cn"));
        var composition = RuntimeComposition.builder(RuntimeProfile.local())
                .install(RuntimeBasics.module(config, () -> ZeroRuntimeExecutors.localPrototype("recipe-basics", 2)));
        // 诊断只规划依赖；执行器工厂在 build 时才创建资源。
        int componentCount = composition.diagnose().components().size();
        var runtime = composition.build();
        String region;
        String fallback;
        String worker;
        boolean started;
        try (runtime) {
            runtime.start();
            started = runtime.running();
            var settings = runtime.require(RuntimeBasics.CONFIG);
            region = settings.require("game.region");
            fallback = settings.getOrDefault("game.language", "zh-CN");
            worker = CompletableFuture.supplyAsync(() -> Thread.currentThread().getName(),
                    runtime.require(RuntimeBasics.EXECUTORS).logicExecutor()).get(5, TimeUnit.SECONDS);
        }
        return new Result(componentCount, region, fallback, worker, started, runtime.runtimeState() == RuntimeState.CLOSED);
    }

    /**
     * 生命周期与配置的不可变结果；线程安全。
     * @param componentCount 已规划组件数量。
     * @param region 显式业务配置。
     * @param language 缺省语言。
     * @param worker 实际执行任务的线程名。
     * @param started 启动后是否运行。
     * @param stopped 关闭后是否停止。
     * @author zn
     */
    public record Result(int componentCount, String region, String language, String worker,
                         boolean started, boolean stopped) { }
}
