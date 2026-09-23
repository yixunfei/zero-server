package group.zn.zero.runtime.production;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import group.zn.zero.core.error.SystemErrorCode;
import group.zn.zero.core.error.ZeroException;
import group.zn.zero.runtime.production.ProductionAdapterErrorCode;
import group.zn.zero.runtime.production.ProductionAdapterException;
import group.zn.zero.runtime.production.ProductionAdapterFailurePhase;
import group.zn.zero.runtime.production.ProductionAdapterFailures;
import java.io.PrintWriter;
import java.io.StringWriter;
import org.junit.jupiter.api.Test;

/**
 * Production Adapter 安全异常图测试。
 *
 * @author zn
 */
class ProductionAdapterExceptionSecurityTest {

    /** 用于反证第三方异常文本不会穿透安全边界的敏感哨兵。 */
    private static final String SECRET = "PAF1-EXCEPTION-SECRET-SENTINEL";

    /**
     * 验证安全转换保留可信归因，并从消息、cause、suppressed、摘要和完整堆栈中移除敏感原值。
     */
    @Test
    void sanitizeShouldKeepTrustedAttributionWithoutRetainingRawThrowableGraph() {
        IllegalStateException rawCause = new IllegalStateException(SECRET + "-cause");
        IllegalArgumentException rawFailure = new IllegalArgumentException(SECRET + "-primary", rawCause);
        AssertionError rawSuppressed = new AssertionError(SECRET + "-suppressed");
        rawSuppressed.addSuppressed(new IllegalStateException(SECRET + "-nested-suppressed"));
        rawFailure.addSuppressed(rawSuppressed);

        ProductionAdapterException failure = ProductionAdapterFailures.sanitize(
                "kafka-rpc",
                ProductionAdapterFailurePhase.CONNECT,
                ProductionAdapterErrorCode.CONNECTION_FAILED,
                ProductionAdapterErrorCode.CONNECTION_FAILED.message(),
                rawFailure);

        assertEquals("kafka-rpc", failure.adapterName());
        assertEquals(ProductionAdapterFailurePhase.CONNECT, failure.failurePhase());
        assertSame(ProductionAdapterErrorCode.CONNECTION_FAILED, failure.errorCode());
        assertEquals(ProductionAdapterErrorCode.CONNECTION_FAILED.message(), failure.message());
        assertEquals(failure.message(), failure.getMessage());
        assertNull(failure.getCause());
        assertEquals(1, failure.getSuppressed().length);

        ProductionAdapterException safeSuppressed = assertInstanceOf(
                ProductionAdapterException.class,
                failure.getSuppressed()[0]);
        assertNotSame(rawSuppressed, safeSuppressed);
        assertEquals("kafka-rpc", safeSuppressed.adapterName());
        assertEquals(ProductionAdapterFailurePhase.ROLLBACK, safeSuppressed.failurePhase());
        assertSame(ProductionAdapterErrorCode.ROLLBACK_FAILED, safeSuppressed.errorCode());
        assertEquals(ProductionAdapterErrorCode.ROLLBACK_FAILED.message(), safeSuppressed.message());
        assertNull(safeSuppressed.getCause());
        assertEquals(1, safeSuppressed.getSuppressed().length);

        ProductionAdapterException safeNested = assertInstanceOf(
                ProductionAdapterException.class,
                safeSuppressed.getSuppressed()[0]);
        assertEquals(ProductionAdapterFailurePhase.ROLLBACK, safeNested.failurePhase());
        assertSame(ProductionAdapterErrorCode.ROLLBACK_FAILED, safeNested.errorCode());
        assertNull(safeNested.getCause());

        String exposed = failure.message()
                + failure.toString()
                + safeSuppressed.message()
                + safeSuppressed.toString()
                + safeNested.message()
                + safeNested.toString()
                + stackTrace(failure);
        assertFalse(exposed.contains(SECRET), exposed);
        assertFalse(exposed.contains(rawFailure.getClass().getName()), exposed);
        assertFalse(exposed.contains(rawCause.getClass().getName()), exposed);
        assertFalse(exposed.contains(rawSuppressed.getClass().getName()), exposed);
    }

    /**
     * 验证 sanitizer 只复制 Starter wrapper 直接归集的 cleanup suppressed，不遍历或发布 raw cause 图。
     */
    @Test
    void sanitizeShouldCopyDirectCleanupSuppressedWithoutPublishingRawCause() {
        AssertionError rawPrimary = new AssertionError(SECRET + "-wrapped-primary");
        rawPrimary.addSuppressed(new IllegalStateException(SECRET + "-third-party-suppressed"));
        ZeroException lifecycleWrapper = ZeroException.of(
                SystemErrorCode.SYSTEM_ERROR,
                SECRET + "-wrapper",
                rawPrimary);
        lifecycleWrapper.addSuppressed(new IllegalStateException(SECRET + "-first-cleanup"));
        lifecycleWrapper.addSuppressed(new AssertionError(SECRET + "-second-cleanup"));

        ProductionAdapterException failure = ProductionAdapterFailures.sanitize(
                "kafka-rpc",
                ProductionAdapterFailurePhase.STARTUP,
                ProductionAdapterErrorCode.STARTUP_FAILED,
                ProductionAdapterErrorCode.STARTUP_FAILED.message(),
                lifecycleWrapper);

        assertNull(failure.getCause());
        assertEquals(2, failure.getSuppressed().length);
        for (Throwable suppressed : failure.getSuppressed()) {
            ProductionAdapterException rollback = assertInstanceOf(
                    ProductionAdapterException.class,
                    suppressed);
            assertEquals("kafka-rpc", rollback.adapterName());
            assertEquals(ProductionAdapterFailurePhase.ROLLBACK, rollback.failurePhase());
            assertSame(ProductionAdapterErrorCode.ROLLBACK_FAILED, rollback.errorCode());
            assertNull(rollback.getCause());
        }
        assertFalse(stackTrace(failure).contains(SECRET), stackTrace(failure));
    }

    /**
     * 把完整异常图打印成与日志框架常见输出一致的文本。
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
}
