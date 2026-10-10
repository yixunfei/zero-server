package group.zn.zero.net.kcp;

import group.zn.zero.protocol.ProtocolFrame;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** 等待逐帧本地接收的有限响应批次；不等远端 ACK，不在锁内调用端点。 @author zn */
final class KcpResponseBatch {
    /** 发送端点。 */
    private final KcpDispatchEndpoint endpoint;
    /** 已经由调度器冻结的有限批次。 */
    private final List<ProtocolFrame> frames;
    /** 关闭门控。 */
    private final BooleanSupplier closed;
    /** 全批次物理接收结果。 */
    private final CompletableFuture<Void> result = new CompletableFuture<>();
    /** 无递归单消费者推进计数。 */
    private final AtomicInteger work = new AtomicInteger();
    /** 只由推进者访问的下一帧下标。 */
    private int index;
    /** 当前本地接收阶段是否尚未完成。 */
    private volatile boolean waiting;
    /** 首个失败；在 waiting=false 发布前写入。 */
    private volatile Throwable failure;

    KcpResponseBatch(final KcpDispatchEndpoint endpoint, final List<ProtocolFrame> frames, final BooleanSupplier closed) {
        this.endpoint = endpoint;
        this.frames = frames;
        this.closed = closed;
    }

    CompletionStage<Void> start() { advance(); return result; }

    private void advance() {
        if (work.getAndIncrement() != 0) return;
        int pending = 1;
        do {
            if (!waiting && !result.isDone()) {
                if (failure != null) result.completeExceptionally(failure);
                else if (index == frames.size() || closed.getAsBoolean()) result.complete(null);
                else sendNext();
            }
            pending = work.addAndGet(-pending);
        } while (pending != 0);
    }

    private void sendNext() {
        waiting = true;
        var settled = new AtomicBoolean();
        try {
            Objects.requireNonNull(endpoint.respond(frames.get(index++)), "responseStage")
                    .whenComplete((ignored, cause) -> completed(settled, cause));
        } catch (RuntimeException | Error cause) {
            completed(settled, cause);
        }
    }

    private void completed(final AtomicBoolean settled, final Throwable cause) {
        if (!settled.compareAndSet(false, true)) return;
        failure = cause;
        waiting = false;
        advance();
    }
}
