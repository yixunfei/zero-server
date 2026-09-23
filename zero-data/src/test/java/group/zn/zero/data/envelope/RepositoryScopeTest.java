package group.zn.zero.data.envelope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.core.error.ZeroException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

class RepositoryScopeTest {
    @Test
    void closeWaitsForInflightStoresWhileIndependentAccessCanProceed() throws Exception {
        var scope = new RepositoryScope();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var operation = executor.submit(() -> scope.access(() -> {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("store was not released");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                return 7;
            }));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertEquals(9, scope.access(() -> 9));
                var closing = executor.submit(scope::close);
                assertThrows(TimeoutException.class, () -> closing.get(100, TimeUnit.MILLISECONDS));
                release.countDown();
                assertEquals(7, operation.get(5, TimeUnit.SECONDS));
                closing.get(5, TimeUnit.SECONDS);
                assertThrows(ZeroException.class, () -> scope.access(() -> 1));
            } finally {
                release.countDown();
            }
        }
    }
}
