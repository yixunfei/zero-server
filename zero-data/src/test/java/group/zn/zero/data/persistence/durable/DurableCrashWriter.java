package group.zn.zero.data.persistence.durable;

import group.zn.zero.data.envelope.InMemoryEnvelopeStore;
import group.zn.zero.data.envelope.ZeroDataEnvelope;
import group.zn.zero.data.persistence.DataThreadBinding;
import group.zn.zero.data.persistence.DirectPersistenceBindingExecutor;
import group.zn.zero.data.persistence.PersistenceRetryPolicy;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.Executors;

/** 子 JVM 在耐久确认后立即 halt，故意不运行关闭钩子。 @author zn */
public final class DurableCrashWriter {
    private DurableCrashWriter() { }
    /** @param args 唯一日志目录；测试夹具不访问外部后端。 */
    public static void main(final String[] args) {
        var service = new DurablePersistenceService(new LocalDurableJournal(Path.of(args[0]), 1_000_000, 100),
                Map.of("players", new InMemoryEnvelopeStore()), new DirectPersistenceBindingExecutor(),
                Executors.newSingleThreadExecutor(), Clock.systemUTC(), PersistenceRetryPolicy.defaults());
        service.submit("crash-op", "players", 0, DataThreadBinding.unbound(),
                () -> new ZeroDataEnvelope("game", "players", "p", 1, 1, 1, 1, new byte[] {7}))
                .toCompletableFuture().join();
        Runtime.getRuntime().halt(0);
    }
}
