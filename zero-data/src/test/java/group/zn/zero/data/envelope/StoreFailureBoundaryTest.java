package group.zn.zero.data.envelope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import group.zn.zero.core.error.ZeroException;
import group.zn.zero.data.error.DataErrorCode;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** 存储异常诊断链与强制原子 CAS 契约。 @author zn */
class StoreFailureBoundaryTest {
    /** 未实现 CAS 的第三方存储不允许退化成先读后写。 */
    @Test void unsupportedCasDoesNotReadOrWrite() {
        var store = new FailingStore(new AssertionError("must not access backend"));
        ZeroException failure = assertThrows(ZeroException.class, () -> store.saveIfVersion(envelope(), 0));
        assertEquals(DataErrorCode.ATOMIC_WRITE_UNSUPPORTED, failure.errorCode());
    }

    /** 读取、写入与删除边界保留原始驱动异常，已有数据错误码不被泛化。 */
    @Test void scopedStorePreservesCauseAndSafeMessage() {
        var driver = new IllegalStateException("sensitive connection detail");
        for (RuntimeException cause : List.of(driver, ZeroException.of(DataErrorCode.VERSION_CONFLICT,
                "backend detail", driver))) {
            var store = new ScopedEnvelopeStore(new RepositoryScope(), new FailingStore(cause));
            assertFailure(() -> store.findById("id"), cause, DataErrorCode.READ_FAILED);
            assertFailure(store::findAll, cause, DataErrorCode.READ_FAILED);
            assertFailure(store::count, cause, DataErrorCode.READ_FAILED);
            assertFailure(() -> store.save(envelope()), cause, DataErrorCode.WRITE_FAILED);
            assertFailure(() -> store.deleteById("id"), cause, DataErrorCode.DELETE_FAILED);
        }
    }

    private void assertFailure(Runnable action, RuntimeException cause, DataErrorCode fallback) {
        ZeroException failure = assertThrows(ZeroException.class, action::run);
        var code = cause instanceof ZeroException zero ? zero.errorCode() : fallback;
        assertEquals(code, failure.errorCode());
        assertEquals(code.message(), failure.getMessage());
        assertSame(cause, failure.getCause());
    }

    private ZeroDataEnvelope envelope() {
        return new ZeroDataEnvelope("test", "store", "id", 1, 1, 1, 0, new byte[]{1});
    }

    /** 所有后端入口抛出指定故障；故意不覆盖默认 CAS。 */
    private static final class FailingStore implements ZeroDataEnvelopeStore {
        /** 模拟原始故障。 */
        private final Throwable failure;
        private FailingStore(Throwable failure) { this.failure = failure; }
        private <T> T fail() {
            if (failure instanceof Error error) throw error;
            throw (RuntimeException) failure;
        }
        /** @return 抛出模拟读取故障。 */
        @Override public Optional<ZeroDataEnvelope> findById(String id) { return fail(); }
        /** @return 抛出模拟读取故障。 */
        @Override public List<ZeroDataEnvelope> findAll() { return fail(); }
        /** 抛出模拟写入故障。 */
        @Override public void save(ZeroDataEnvelope value) { fail(); }
        /** 抛出模拟删除故障。 */
        @Override public void deleteById(String id) { fail(); }
        /** @return 抛出模拟计数故障。 */
        @Override public long count() { return fail(); }
    }
}
