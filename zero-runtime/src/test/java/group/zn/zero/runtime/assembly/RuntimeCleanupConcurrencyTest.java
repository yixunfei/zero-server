package group.zn.zero.runtime.assembly;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import group.zn.zero.core.lifecycle.Lifecycle;
import group.zn.zero.core.lifecycle.LifecycleState;
import group.zn.zero.runtime.api.ComponentId;
import group.zn.zero.runtime.api.ComponentKey;
import group.zn.zero.runtime.api.GameRuntime;
import group.zn.zero.runtime.api.RuntimeState;
import group.zn.zero.runtime.diagnostics.RuntimeAssemblyException;
import group.zn.zero.runtime.spi.ComponentContribution;
import group.zn.zero.runtime.spi.ComponentDescriptor;
import group.zn.zero.runtime.spi.RuntimeProviders;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** 清理可观测性、重入及登记封闭的确定性回归。 @author zn */
class RuntimeCleanupConcurrencyTest {
    /** 测试能力。 */
    private static final ComponentKey<String> VALUE = ComponentKey.single("cleanup.value", String.class);
    /** 外部关闭等待并发报告不得死锁。 */
    @Test void resourceCloseCanWaitForConcurrentReport() {
        var ref = new AtomicReference<GameRuntime>();
        var runtime = build(context -> {
            context.resources().register((AutoCloseable) () -> {
                var reported = new CompletableFuture<Integer>();
                Thread.ofVirtual().start(() -> reported.complete(ref.get().report().pendingResourceCloseCount()));
                assertEquals(1, reported.get(2, TimeUnit.SECONDS));
            });
            return ComponentContribution.builder().bind(VALUE, "value").build();
        });
        ref.set(runtime);
        assertDoesNotThrow(runtime::close);
        assertEquals(0, runtime.report().pendingResourceCloseCount());
    }
    /** 启动回调请求关闭不能造成关闭后复活。 */
    @Test void startCannotResurrectClosedResources() {
        var ref = new AtomicReference<GameRuntime>();
        var closed = new AtomicInteger();
        var runtime = build(context -> {
            context.resources().register((AutoCloseable) closed::incrementAndGet);
            return ComponentContribution.builder().bind(VALUE, "value")
                    .lifecycle(new CallbackLifecycle(() -> ref.get().close(), () -> { })).build();
        });
        ref.set(runtime);
        assertThrows(RuntimeAssemblyException.class, runtime::start);
        assertEquals(RuntimeState.FAILED, runtime.runtimeState());
        assertEquals(1, closed.get());
        runtime.close();
        assertEquals(1, closed.get());
    }
    /** 资源与 lifecycle 关闭回调重入只清理一次。 */
    @Test void closeCallbacksDoNotRepeatCleanup() {
        var ref = new AtomicReference<GameRuntime>();
        var closed = new AtomicInteger();
        var stopped = new AtomicInteger();
        var runtime = build(context -> {
            context.resources().register((AutoCloseable) () -> { if (closed.incrementAndGet() == 1) ref.get().close(); });
            return ComponentContribution.builder().bind(VALUE, "value").lifecycle(new CallbackLifecycle(
                    () -> { }, () -> { if (stopped.incrementAndGet() == 1) ref.get().close(); })).build();
        });
        ref.set(runtime);
        runtime.start();
        runtime.close();
        assertEquals(1, closed.get());
        assertEquals(1, stopped.get());
    }
    /** 回滚开始前登记器必须封闭。 */
    @Test void rollbackRejectsLateResourceRegistration() {
        var rejected = new AtomicReference<RuntimeAssemblyException>();
        assertThrows(RuntimeAssemblyException.class, () -> build(context -> {
            context.resources().register((AutoCloseable) () -> rejected.set(assertThrows(
                    RuntimeAssemblyException.class, () -> context.resources().register((AutoCloseable) () -> { }))));
            throw new IllegalStateException("create failed");
        }));
        assertNotNull(rejected.get());
    }
    private static GameRuntime build(RuntimeProviders.Factory factory) {
        var provider = RuntimeProviders.create(ComponentDescriptor.builder(ComponentId.of("cleanup.provider"))
                .provide(VALUE).build(), factory);
        return RuntimeComposition.builder(RuntimeProfile.local())
                .install(RuntimeModule.of("cleanup.module", List.of(provider), VALUE)).build();
    }
    /** 测试 lifecycle。 */
    private static final class CallbackLifecycle implements Lifecycle {
        /** 启动动作。 */ private final Runnable starting;
        /** 停止动作。 */ private final Runnable stopping;
        /** 当前状态。 */ private LifecycleState state = LifecycleState.NEW;
        private CallbackLifecycle(Runnable starting, Runnable stopping) { this.starting = starting; this.stopping = stopping; }
        /** @return 当前状态。 */ @Override public LifecycleState state() { return state; }
        /** 启动测试组件。 */ @Override public void start() { starting.run(); state = LifecycleState.RUNNING; }
        /** 停止测试组件。 */ @Override public void stop() { stopping.run(); state = LifecycleState.STOPPED; }
    }
}
