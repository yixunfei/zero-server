package group.zn.zero.net.kcp;

import group.zn.zero.core.error.ZeroException;
import group.zn.zero.net.error.NetErrorCode;
import group.zn.zero.protocol.ProtocolFrame;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/** 单连接串行业务调度；短锁保护队列，回调由显式执行器执行，关闭后仍能完成通知。 @author zn */
final class KcpDispatch {
    /** 所属会话。 */
    private final KcpDispatchEndpoint session;
    /** 尚未执行的帧及其预算。 */
    private final Queue<Pending> frames = new ArrayDeque<>();
    /** 正在执行的一帧，关闭不能提前释放其内存预算。 */
    private Pending active;
    /** 正在执行的异步回调。 */
    private boolean busy;
    /** 是否已关闭。 */
    private boolean closed;
    /** 是否已经发出 open 回调。 */
    private boolean opened;
    /** 生命周期回调的固定预算，防止快速签发/撤销绕过全局上限。 */
    private boolean lifecycleReserved = true;
    /** 防止同步执行器和同步完成阶段递归推进业务队列。 */
    private final java.util.concurrent.atomic.AtomicInteger dispatchWork = new java.util.concurrent.atomic.AtomicInteger();

    KcpDispatch(final KcpDispatchEndpoint session) {
        this.session = session;
        if (!session.reserveInbound(512)) throw KcpServer.error(NetErrorCode.INBOUND_OVERFLOW, null);
    }
    void open() { dispatch(); }
    synchronized boolean idle() { return !busy && frames.isEmpty() && active == null; }
    void receive(final ProtocolFrame frame) {
        boolean overflow;
        synchronized (this) {
            if (closed) return;
            long charge = (long) frame.payloadLength() + frame.extensionLength() + 512;
            overflow = frames.size() + (active == null ? 0 : 1) >= session.options().maxQueuedFrames()
                    || !session.reserveInbound(charge);
            if (overflow) { closed = true; discardPending(); }
            else frames.add(new Pending(frame, charge));
        }
        if (overflow) session.closeWithFailure(NetErrorCode.INBOUND_OVERFLOW, null);
        dispatch();
    }
    void close() {
        synchronized (this) { closed = true; discardPending(); }
        dispatch();
    }
    private void dispatch() {
        if (dispatchWork.getAndIncrement() != 0) return;
        int pending = 1;
        do {
            dispatchNext();
            pending = dispatchWork.addAndGet(-pending);
        } while (pending != 0);
    }

    private void dispatchNext() {
        boolean opening;
        boolean closing;
        ProtocolFrame frame;
        synchronized (this) {
            if (busy) return;
            opening = !opened && !closed;
            closing = closed && opened;
            if (!opening && !closing && (closed || frames.isEmpty())) {
                if (closed) releaseLifecycle();
                return;
            }
            if (!opening && !closing) active = frames.remove();
            frame = active == null ? null : active.frame();
            busy = true;
            if (opening) opened = true;
            if (closing) opened = false;
        }
        // 执行器、响应发送与关闭均可能取得客户端生命周期锁，不得在调度锁内调用。
        try {
            session.executor().execute(() -> invoke(opening, closing, frame));
        } catch (RuntimeException | Error failure) {
            synchronized (this) {
                busy = false;
                closed = true;
                opened = false;
                discardPending();
                releaseActive();
                releaseLifecycle();
            }
            session.closeWithFailure(NetErrorCode.HANDLER_FAILED, failure);
        }
    }
    private void invoke(final boolean opening, final boolean closing, final ProtocolFrame frame) {
        var completed = new java.util.concurrent.atomic.AtomicBoolean();
        if (session.inIoThread()) {
            completed(completed, null, ZeroException.of(NetErrorCode.HANDLER_FAILED,
                    "KCP business executor must not inline on IO thread", null));
            return;
        }
        try {
            if (opening) session.listener().onOpen(session.connection());
            else if (closing) session.listener().onClose(session.connection());
            else {
                if (session.closed() || !session.authorized()) {
                    completed(completed, null, KcpServer.error(NetErrorCode.AUTHENTICATION_EXPIRED, null));
                    return;
                }
                CompletionStage<List<ProtocolFrame>> result = Objects.requireNonNull(
                        session.handler().handle(session.connection(), frame), "handlerStage");
                result.whenComplete((responses, cause) -> completed(completed, responses, cause));
                return;
            }
            completed(completed, List.of(), null);
        } catch (RuntimeException | Error failure) {
            completed(completed, null, failure);
        }
    }
    private void completed(final java.util.concurrent.atomic.AtomicBoolean completed,
            final List<ProtocolFrame> responses, final Throwable failure) {
        if (!completed.compareAndSet(false, true)) return;
        if (failure != null) { finish(NetErrorCode.HANDLER_FAILED, failure); return; }
        try {
            if (responses == null || responses.size() > session.options().maxQueuedFrames()) {
                throw KcpServer.error(NetErrorCode.HANDLER_FAILED, null);
            }
            new KcpResponseBatch(session, List.copyOf(responses), this::isClosed).start()
                    .whenComplete((ignored, sendFailure) -> finish(NetErrorCode.SEND_FAILED, sendFailure));
        } catch (RuntimeException | Error sendFailure) {
            finish(NetErrorCode.HANDLER_FAILED, sendFailure);
        }
    }
    private synchronized boolean isClosed() { return closed; }

    private void finish(final NetErrorCode code, final Throwable failure) {
        synchronized (this) {
            releaseActive();
            busy = false;
            if (failure != null) { closed = true; discardPending(); }
        }
        try {
            if (failure != null) session.closeWithFailure(code, failure);
        } finally { dispatch(); }
    }
    private void discardPending() {
        Pending frame;
        while ((frame = frames.poll()) != null) session.releaseInbound(frame.charge());
    }
    private void releaseActive() {
        if (active != null) { session.releaseInbound(active.charge()); active = null; }
    }
    private void releaseLifecycle() {
        if (lifecycleReserved) { lifecycleReserved = false; session.releaseInbound(512); }
    }
    /** 不可变队列项；预算只由调度锁释放。 @author zn */
    private record Pending(ProtocolFrame frame, long charge) { }
}
