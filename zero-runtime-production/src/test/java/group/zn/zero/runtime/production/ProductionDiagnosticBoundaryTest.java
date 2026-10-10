package group.zn.zero.runtime.production;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import group.zn.zero.core.lifecycle.AbstractLifecycle;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 生命周期首失败保留与显式诊断替换边界测试。 @author zn */
class ProductionDiagnosticBoundaryTest {
    @Test void stopFailurePreservesStartupDiagnosticButExplicitFailCanReplaceIt() {
        var diagnostic = new ProductionAdapterDiagnostic("adapter", ZeroProductionAdapterState.CREATED,
                List.of(), List.of(), List.of(), List.of(), List.of());
        var lifecycle = new ProductionAdapterLifecycle(diagnostic,
                new ProductionStartupBudget(Duration.ofSeconds(2), Duration.ofSeconds(1)), new AbstractLifecycle() {
                    @Override protected void doStart() { throw new IllegalStateException("private-start"); }
                    @Override protected void doStop() { throw new IllegalStateException("private-stop"); }
                });
        assertThrows(ProductionAdapterException.class, lifecycle::start);
        var first = diagnostic.snapshot();
        var stopped = assertThrows(ProductionAdapterException.class, lifecycle::stop);
        assertEquals(first, diagnostic.snapshot());
        diagnostic.fail(stopped);
        assertEquals(ProductionAdapterFailurePhase.CLOSE, diagnostic.snapshot().failurePhase());
        diagnostic.mark(ZeroProductionAdapterState.STARTED);
        assertNull(diagnostic.snapshot().errorCode());
        assertEquals(ProductionAdapterFailurePhase.NONE, diagnostic.snapshot().failurePhase());
    }
}
