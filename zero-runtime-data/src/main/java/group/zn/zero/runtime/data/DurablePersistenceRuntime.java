package group.zn.zero.runtime.data;

import group.zn.zero.data.envelope.ZeroDataEnvelopeStore;
import group.zn.zero.data.persistence.PersistenceBindingExecutor;
import group.zn.zero.data.persistence.PersistenceRetryPolicy;
import group.zn.zero.data.persistence.durable.DurablePersistenceService;
import group.zn.zero.data.persistence.durable.LocalDurableJournal;
import group.zn.zero.runtime.api.ComponentId;
import group.zn.zero.runtime.api.ComponentKey;
import group.zn.zero.runtime.assembly.RuntimeModule;
import group.zn.zero.runtime.bootstrap.PeriodicAsyncTask;
import group.zn.zero.runtime.bootstrap.RuntimeBasics;
import group.zn.zero.runtime.spi.ComponentContribution;
import group.zn.zero.runtime.spi.ComponentDescriptor;
import group.zn.zero.runtime.spi.ComponentKind;
import group.zn.zero.runtime.spi.RuntimeProviders;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** 显式耐久提交装配；应用拥有 targets，运行时拥有日志、周期重放和关闭顺序。 @author zn */
public final class DurablePersistenceRuntime {
    /** 显式提交能力；与周期内存标脏能力分开，避免隐式改变确认契约。 */
    public static final ComponentKey<DurablePersistenceService> SERVICE = ComponentKey.single(
            "zero.data.durable", DurablePersistenceService.class);
    private DurablePersistenceRuntime() { }

    /** 创建按需模块；build 冷路径恢复日志，start 后每秒重放最多 128 条。
     * @param directory 独占日志目录。 @param targets 应用拥有的后端映射；关闭必须晚于此模块。
     * @param bindingExecutor 实体快照执行域。 @return 未启动模块；无额外兼容入口。 */
    public static RuntimeModule module(final Path directory, final Map<String, ZeroDataEnvelopeStore> targets,
            final PersistenceBindingExecutor bindingExecutor) {
        return module(directory, targets, bindingExecutor, 256L * 1024 * 1024, 100_000);
    }

    /** 创建显式日志预算的模块；恢复时不能缩小到已有日志大小/收据数量以下。
     * @param directory 独占日志目录。 @param targets 应用拥有的后端映射。
     * @param bindingExecutor 实体绑定执行器。 @param maxBytes 日志字节预算，至少 1024。
     * @param maxOperations 包含完成收据的操作数预算，至少 1。
     * @return 未启动模块；预算在 build 时校验，磁盘异常或超限明确失败。 */
    public static RuntimeModule module(final Path directory, final Map<String, ZeroDataEnvelopeStore> targets,
            final PersistenceBindingExecutor bindingExecutor, final long maxBytes, final int maxOperations) {
        Map<String, ZeroDataEnvelopeStore> stores = Map.copyOf(targets);
        var provider = RuntimeProviders.create(ComponentDescriptor.builder(ComponentId.of("zero.data.durable-provider"))
                .provide(SERVICE).require(RuntimeBasics.EXECUTORS).kind(ComponentKind.FOUNDATION).build(), context -> {
                    var executors = context.require(RuntimeBasics.EXECUTORS);
                    if (executors.remoteIoMayInline()) throw new IllegalStateException("durable persistence requires remote IO execution");
                    var journal = context.resources().register(new LocalDurableJournal(directory, maxBytes, maxOperations));
                    var service = context.resources().register(new DurablePersistenceService(journal, stores,
                            bindingExecutor, executors.remoteIoExecutor(), Clock.systemUTC(), PersistenceRetryPolicy.defaults()));
                    var maintenance = new PeriodicAsyncTask(executors, "durable-flush", Duration.ofSeconds(1),
                            () -> service.flushNow(128));
                    return ComponentContribution.builder().bind(SERVICE, service).lifecycle(maintenance).build();
                });
        return RuntimeModule.of("zero.data.durable", List.of(provider), SERVICE);
    }
}
