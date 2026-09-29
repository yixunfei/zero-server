package group.zn.zero.net.kcp;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/** 把存储完成送回会话 EventLoop，计时器同属受管 IO，不创建全局线程。 @author zn */
final class KcpStoreCalls {
    private KcpStoreCalls() { }
    static <T> CompletionStage<T> call(final KcpServer server, final Supplier<CompletionStage<T>> operation) {
        var result = new CompletableFuture<T>();
        var loop = server.channel().eventLoop();
        var timeout = loop.schedule(() -> result.completeExceptionally(new TimeoutException("KCP ownership operation timed out")),
                server.sessionServices().operationTimeout().toNanos(), TimeUnit.NANOSECONDS);
        try {
            operation.get().whenComplete((value, failure) -> {
                Runnable finish = () -> {
                    timeout.cancel(false);
                    if (failure == null) result.complete(value); else result.completeExceptionally(failure);
                };
                if (loop.inEventLoop()) finish.run();
                else try { loop.execute(finish); }
                catch (RuntimeException rejected) { timeout.cancel(false); result.completeExceptionally(rejected); }
            });
        } catch (RuntimeException failure) { timeout.cancel(false); result.completeExceptionally(failure); }
        return result;
    }
}
