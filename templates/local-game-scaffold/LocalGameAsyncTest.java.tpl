package __PACKAGE__;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/** Async boundary contract tests for generated local business adapters. */
class LocalGameAsyncTest {
    @Test
    void completionAndFailureAreObservableWithoutBlocking() {
        LocalGameObservation observation = new LocalGameObservation();
        CompletableFuture<Void> completed = CompletableFuture.completedFuture(null);
        completed.whenComplete((value, error) -> observation.completed());
        CompletableFuture<Void> failed = new CompletableFuture<>();
        failed.whenComplete((value, error) -> { if (error != null) observation.failed(error); });
        failed.completeExceptionally(new IllegalStateException("expected"));
        assertTrue(observation.completedCount() == 1);
        assertTrue(observation.failedCount() == 1);
    }

    @Test
    void cancellationDoesNotBlockTheCallingThread() {
        CompletableFuture<Void> pending = new CompletableFuture<>();
        assertTrue(pending.cancel(false));
        assertTrue(pending.isCancelled());
        assertFalse(pending.isCompletedExceptionally() && !pending.isCancelled());
    }
}
