package group.zn.zero.runtime.production;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import group.zn.zero.core.config.MapZeroConfig;
import group.zn.zero.core.lifecycle.LifecycleState;
import group.zn.zero.runtime.api.ComponentId;
import group.zn.zero.runtime.api.ComponentKey;
import group.zn.zero.runtime.assembly.RuntimeModule;
import group.zn.zero.runtime.health.HealthPhase;
import group.zn.zero.runtime.spi.ComponentContribution;
import group.zn.zero.runtime.spi.ComponentDescriptor;
import group.zn.zero.runtime.spi.ComponentKind;
import group.zn.zero.runtime.spi.RuntimeComponentProvider;
import group.zn.zero.runtime.spi.RuntimeProviders;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * 验证 production 的按需选择不会把显式外部组件失败变成本地回退。
 *
 * @author zn
 */
class ProductionAssemblySelectionTest {

    /** 传输中立的测试服务；不访问任何真实中间件。 */
    private static final ComponentKey<String> SERVICE = ComponentKey.single("test.service", String.class);

    /** 外部实现必须声明启动健康；缺失声明时两个工厂都不得执行。 */
    @Test
    void missingExternalStartupHealthMustFailBeforeCreatingEitherProvider() {
        AtomicInteger localCreated = new AtomicInteger();
        AtomicInteger externalCreated = new AtomicInteger();
        var external = RuntimeProviders.create(externalDescriptor().build(), context -> {
            externalCreated.incrementAndGet();
            return ComponentContribution.builder().bind(SERVICE, "external").build();
        });

        ProductionAssembly assembly = assembly(localCreated, external);
        var failure = assertThrows(ProductionAdapterException.class, assembly::plan);

        assertEquals(ProductionAdapterFailurePhase.CONFIG_VALIDATION, failure.failurePhase());
        assertEquals(ProductionAdapterErrorCode.CONFIG_INVALID, failure.errorCode());
        assertEquals(0, localCreated.get());
        assertEquals(0, externalCreated.get());
    }

    /** 外部创建失败时回收已登记资源，并拒绝使用原有本地选择。 */
    @Test
    void externalCreationFailureMustRollBackWithoutLocalFallback() {
        AtomicInteger localCreated = new AtomicInteger();
        AtomicInteger closed = new AtomicInteger();
        var external = RuntimeProviders.create(externalDescriptor().health(HealthPhase.STARTUP).build(), context -> {
            context.resources().register(() -> closed.incrementAndGet());
            throw new IllegalStateException("private-client-detail");
        });

        ProductionAssembly assembly = assembly(localCreated, external);
        var failure = assertThrows(ProductionAdapterException.class, assembly::build);

        assertEquals(ProductionAdapterErrorCode.CLIENT_CREATION_FAILED, failure.errorCode());
        assertEquals(0, localCreated.get());
        assertEquals(1, closed.get());
        assertNull(failure.getCause());
        assertFalse(failure.toString().contains("private-client-detail"));
    }

    /** 外部健康失败会终止启动、释放资源；再次关闭不会重复释放或创建本地实现。 */
    @Test
    void externalStartupHealthFailureMustStopWithoutLocalFallback() {
        AtomicInteger localCreated = new AtomicInteger();
        AtomicInteger probes = new AtomicInteger();
        AtomicInteger closed = new AtomicInteger();
        var external = RuntimeProviders.create(externalDescriptor().health(HealthPhase.STARTUP).build(), context -> {
            context.resources().register(() -> closed.incrementAndGet());
            return ComponentContribution.builder().bind(SERVICE, "external")
                    .healthProbe(HealthPhase.STARTUP, request -> {
                        probes.incrementAndGet();
                        return CompletableFuture.failedFuture(new IllegalStateException("private-health-detail"));
                    }).build();
        });

        try (var runtime = assembly(localCreated, external).build()) {
            assertEquals("external", runtime.require(SERVICE));
            var failure = assertThrows(ProductionAdapterException.class, runtime::start);
            assertEquals(ProductionAdapterErrorCode.STARTUP_FAILED, failure.errorCode());
            assertFalse(runtime.state() == LifecycleState.RUNNING);
            assertEquals(1, probes.get());
            assertEquals(1, closed.get());
            assertNull(failure.getCause());
            assertFalse(failure.toString().contains("private-health-detail"));
        }
        assertEquals(0, localCreated.get());
        assertEquals(1, closed.get());
    }

    /** 构造先选择本地实现、再显式启用外部替代实现的组合器。 */
    private ProductionAssembly assembly(
            final AtomicInteger localCreated, final RuntimeComponentProvider external) {
        var local = RuntimeProviders.create(ComponentDescriptor.builder(ComponentId.of("test.local"))
                .provide(SERVICE).kind(ComponentKind.LOCAL).build(), context -> {
                    localCreated.incrementAndGet();
                    return ComponentContribution.builder().bind(SERVICE, "local").build();
                });
        return ProductionAssembly.builder(new MapZeroConfig(Map.of()))
                .configSourceLookups(key -> null, key -> null)
                .base(RuntimeModule.of("test.local-base", List.of(local), SERVICE))
                .install(context -> new ProductionModule(List.of(external), List.of(), List.of()));
    }

    /** 返回调用方私有、可变的外部组件描述器构造器。 */
    private ComponentDescriptor.Builder externalDescriptor() {
        return ComponentDescriptor.builder(ComponentId.of("test.external"))
                .provide(SERVICE).kind(ComponentKind.EXTERNAL);
    }
}
