package group.zn.zero.starter.production;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link ProductionResourceScope} 构建资源事务测试。
 *
 * @author zn
 */
class ProductionResourceScopeTest {

    /** 用于反证回滚异常不会暴露底层资源文本的敏感哨兵。 */
    private static final String SECRET = "PAF1-SCOPE-SECRET-SENTINEL";

    /**
     * 验证提交快照保持创建顺序、不可变，且提交后拒绝继续登记资源。
     */
    @Test
    void commitShouldReturnImmutableCreationOrderSnapshot() {
        ProductionResourceScope scope = new ProductionResourceScope();
        TrackingCloseable first = new TrackingCloseable("first", new ArrayList<>(), null);
        TrackingCloseable second = new TrackingCloseable("second", new ArrayList<>(), null);

        assertSame(first, scope.register(first));
        assertSame(second, scope.register(second));

        List<AutoCloseable> committed = scope.commit();

        assertEquals(List.of(first, second), committed);
        assertThrows(UnsupportedOperationException.class, () -> committed.add(() -> { }));
        assertThrows(
                IllegalStateException.class,
                () -> scope.register(new TrackingCloseable("late", new ArrayList<>(), null)));
    }

    /**
     * 验证构建回滚严格按创建逆序执行，并把所有关闭失败转换为安全 suppressed。
     */
    @Test
    void rollbackShouldCloseInReverseOrderAndSanitizeEveryFailure() {
        List<String> steps = new ArrayList<>();
        ProductionResourceScope scope = new ProductionResourceScope();
        scope.register(new TrackingCloseable(
                "first",
                steps,
                new IllegalStateException(SECRET + "-first")));
        scope.register(new TrackingCloseable("second", steps, null));
        scope.register(new TrackingCloseable(
                "third",
                steps,
                new AssertionError(SECRET + "-third")));
        ProductionAdapterException primary = ProductionAdapterFailures.failure(
                "production-runtime",
                ProductionAdapterFailurePhase.CONFIG_VALIDATION,
                ProductionAdapterErrorCode.CONFIG_INVALID,
                ProductionAdapterErrorCode.CONFIG_INVALID.message());

        scope.rollback(primary);

        assertEquals(List.of("close:third", "close:second", "close:first"), steps);
        assertEquals(2, primary.getSuppressed().length);
        for (Throwable suppressed : primary.getSuppressed()) {
            ProductionAdapterException safeFailure = assertInstanceOf(
                    ProductionAdapterException.class,
                    suppressed);
            assertEquals("production-resource", safeFailure.adapterName());
            assertEquals(ProductionAdapterFailurePhase.ROLLBACK, safeFailure.failurePhase());
            assertSame(ProductionAdapterErrorCode.ROLLBACK_FAILED, safeFailure.errorCode());
            assertEquals(ProductionAdapterErrorCode.ROLLBACK_FAILED.message(), safeFailure.message());
            assertNull(safeFailure.getCause());
        }
        assertFalse(stackTrace(primary).contains(SECRET), stackTrace(primary));
    }

    /**
     * 把完整异常图打印为文本，供敏感哨兵反证。
     *
     * @param failure 待打印异常；不可为空。
     * @return 完整堆栈文本；不可为空，调用方可变，方法不修改异常图。
     */
    private String stackTrace(final Throwable failure) {
        StringWriter writer = new StringWriter();
        try (PrintWriter printer = new PrintWriter(writer)) {
            failure.printStackTrace(printer);
        }
        return writer.toString();
    }

    /**
     * 记录关闭顺序并按需抛出预设失败的测试资源。
     *
     * @author zn
     */
    private static final class TrackingCloseable implements AutoCloseable {

        /** 资源稳定名称。 */
        private final String name;

        /** 有序关闭记录，仅由测试线程访问。 */
        private final List<String> steps;

        /** 关闭时抛出的预设失败；为空表示成功。 */
        private final Throwable closeFailure;

        /**
         * 创建测试资源。
         *
         * @param name 资源名称；不可为空。
         * @param steps 有序关闭记录；不可为空，仅由测试线程访问。
         * @param closeFailure 预设关闭失败；可为空。
         */
        private TrackingCloseable(
                final String name,
                final List<String> steps,
                final Throwable closeFailure) {
            this.name = name;
            this.steps = steps;
            this.closeFailure = closeFailure;
        }

        /**
         * 记录关闭动作并按需抛出预设失败。
         *
         * @throws RuntimeException 预设运行时关闭失败时抛出。
         * @throws Error 预设严重关闭失败时抛出。
         */
        @Override
        public void close() {
            steps.add("close:" + name);
            if (closeFailure instanceof RuntimeException runtimeFailure) {
                throw runtimeFailure;
            }
            if (closeFailure instanceof Error error) {
                throw error;
            }
        }
    }
}
