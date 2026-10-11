package group.zn.zero.runtime.assembly;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import group.zn.zero.runtime.api.ComponentId;
import group.zn.zero.runtime.api.ComponentKey;
import group.zn.zero.runtime.diagnostics.RuntimeAssemblyException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** 组件短入口的依赖规划、延迟创建与资源所有权回归。 */
class RuntimeComponentBuilderTest {
    /** 调用方提供的共享依赖。 */
    private static final ComponentKey<String> INPUT = ComponentKey.single("test.input", String.class);
    /** 工厂创建的自有资源。 */
    private static final ComponentKey<Owned> RESOURCE = ComponentKey.single("test.resource", Owned.class);

    /** 规划不创建对象，依赖由构造器取得，自有资源在正常关闭时释放一次。 */
    @Test
    void createsAfterPlanningAndClosesOwnedResource() {
        AtomicInteger created = new AtomicInteger();
        AtomicInteger closed = new AtomicInteger();
        var composition = composition(created, closed);
        composition.diagnose();
        assertEquals(0, created.get());
        try (var runtime = composition.build()) {
            runtime.start();
            assertEquals("input", runtime.require(RESOURCE).value());
            assertEquals(1, created.get());
        }
        assertEquals(1, closed.get());
    }

    /** 下游工厂失败不遗留前面已创建的 AutoCloseable。 */
    @Test
    void laterCreationFailureRollsBackOwnedResource() {
        AtomicInteger closed = new AtomicInteger();
        var broken = ComponentKey.single("test.broken", String.class);
        var composition = composition(new AtomicInteger(), closed).install(
                RuntimeModule.component(ComponentId.of("app.broken"), broken).require(RESOURCE)
                        .create(context -> { throw new IllegalStateException("failure"); }));
        assertThrows(RuntimeAssemblyException.class, composition::build);
        assertEquals(1, closed.get());
    }

    /** 缺少依赖在规划时失败；借用值不自动转移资源所有权。 */
    @Test
    void missingDependenciesFailBeforeCreationAndBorrowedValuesAreNotClosed() {
        AtomicInteger calls = new AtomicInteger();
        var composition = RuntimeComposition.builder(RuntimeProfile.local()).install(
                RuntimeModule.component(ComponentId.of("app.resource"), RESOURCE).require(INPUT)
                        .create(context -> { calls.incrementAndGet(); return new Owned("", calls); }));
        assertThrows(RuntimeAssemblyException.class, composition::diagnose);
        assertEquals(0, calls.get());
        try (var runtime = RuntimeComposition.builder(RuntimeProfile.local()).install(
                RuntimeModule.value(ComponentId.of("app.borrowed"), RESOURCE, new Owned("", calls))).build()) {
            runtime.start();
        }
        assertEquals(0, calls.get());
    }

    private RuntimeComposition composition(final AtomicInteger created, final AtomicInteger closed) {
        return RuntimeComposition.builder(RuntimeProfile.local())
                .install(RuntimeModule.value(ComponentId.of("app.input"), INPUT, "input"))
                .install(RuntimeModule.component(ComponentId.of("app.resource"), RESOURCE).require(INPUT)
                        .create(context -> {
                            created.incrementAndGet();
                            return new Owned(context.require(INPUT), closed);
                        }));
    }

    /** 模拟需关闭的应用对象。 */
    private record Owned(String value, AtomicInteger closed) implements AutoCloseable {
        @Override public void close() { closed.incrementAndGet(); }
    }
}
