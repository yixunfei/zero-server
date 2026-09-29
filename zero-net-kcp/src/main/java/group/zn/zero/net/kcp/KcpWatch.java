package group.zn.zero.net.kcp;

import group.zn.zero.net.error.NetErrorCode;
import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** 可取消的单订阅指标任务，最多一个在途采样；不在 IO 执行 registry。 @author zn */
public final class KcpWatch implements AutoCloseable {
    /** 已关闭。 */
    private final AtomicBoolean closed = new AtomicBoolean();
    /** 在途采样。 */
    private final AtomicBoolean busy = new AtomicBoolean();
    /** 所属 IO 调度任务。 */
    private final ScheduledFuture<?> timer;
    KcpWatch(final KcpServer server, final Duration interval, final Executor executor, final Consumer<KcpSnapshot> observer) {
        timer = server.channel().eventLoop().scheduleAtFixedRate(() -> {
            if (closed.get() || !busy.compareAndSet(false, true)) return;
            try {
                executor.execute(() -> {
                    try {
                        if (server.channel().eventLoop().inEventLoop()) throw new IllegalArgumentException("metric executor must not inline");
                        if (!closed.get()) observer.accept(server.snapshot());
                    } catch (RuntimeException failure) { server.observeFailure(null, NetErrorCode.OBSERVER_FAILED, failure); }
                    finally { busy.set(false); }
                });
            } catch (RuntimeException failure) { busy.set(false); server.observeFailure(null, NetErrorCode.OBSERVER_FAILED, failure); }
        }, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
        server.channel().closeFuture().addListener(ignored -> close());
    }
    /** 停止新采样；线程安全、幂等，已经执行的观察器由执行器正常完成。 */
    @Override public void close() { closed.set(true); timer.cancel(false); }
    boolean closed() { return closed.get(); }
}
