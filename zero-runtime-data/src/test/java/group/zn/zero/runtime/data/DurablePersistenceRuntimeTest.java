package group.zn.zero.runtime.data;

import static org.junit.jupiter.api.Assertions.assertEquals;

import group.zn.zero.core.config.MapZeroConfig;
import group.zn.zero.data.envelope.InMemoryEnvelopeStore;
import group.zn.zero.data.envelope.ZeroDataEnvelope;
import group.zn.zero.data.persistence.DataThreadBinding;
import group.zn.zero.data.persistence.DirectPersistenceBindingExecutor;
import group.zn.zero.data.persistence.durable.DurableRecord;
import group.zn.zero.data.persistence.durable.LocalDurableJournal;
import group.zn.zero.runtime.assembly.RuntimeComposition;
import group.zn.zero.runtime.assembly.RuntimeProfile;
import group.zn.zero.runtime.bootstrap.RuntimeBasics;
import group.zn.zero.runtime.bootstrap.ZeroRuntimeExecutors;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 装配生命周期必须自动重放，并在关闭后释放日志和执行资源。 @author zn */
class DurablePersistenceRuntimeTest {
    /** 独占测试目录。 */
    @TempDir Path directory;
    /** 完整 build/start/submit/周期 flush/close 后日志可再次打开。 */
    @Test void runtimeSchedulesReplayAndReleasesJournal() throws Exception {
        var store = new InMemoryEnvelopeStore();
        try (var runtime = RuntimeComposition.builder(RuntimeProfile.local())
                .install(RuntimeBasics.module(new MapZeroConfig(Map.of()), ZeroRuntimeExecutors.localPrototype("durable", 1)))
                .install(DurablePersistenceRuntime.module(directory, Map.of("p", store), new DirectPersistenceBindingExecutor()))
                .build()) {
            runtime.start();
            var service = runtime.require(DurablePersistenceRuntime.SERVICE);
            service.submit("op", "p", 0, DataThreadBinding.unbound(),
                    () -> new ZeroDataEnvelope("game", "player", "p", 1, 1, 1, 1, new byte[] {1}))
                    .toCompletableFuture().get(2, TimeUnit.SECONDS);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
            while (service.records().getFirst().state() != DurableRecord.State.APPLIED && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertEquals(DurableRecord.State.APPLIED, service.records().getFirst().state());
        }
        try (var journal = new LocalDurableJournal(directory, 256L * 1024 * 1024, 100_000)) {
            assertEquals(DurableRecord.State.APPLIED, journal.records().getFirst().state());
        }
    }
}
