package group.zn.zero.net.kcp;

import group.zn.zero.core.error.ZeroException;
import group.zn.zero.net.error.NetErrorCode;
import group.zn.zero.protocol.ProtocolFrame;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
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

    KcpDispatch(final KcpDispatchEndpoint session) {
        this.session = session;
        if (!session.reserveInbound(512)) throw KcpServer.error(NetErrorCode.INBOUND_OVERFLOW, null);
    }
    synchronized void open() { dispatch(); }
    synchronized boolean idle() { return !busy && frames.isEmpty() && active == null; }
    synchronized void receive(final ProtocolFrame frame) {
        if (closed) return;
        long charge = (long) frame.payloadLength() + frame.extensionLength() + 512;
        if (frames.size() + (active == null ? 0 : 1) >= session.options().maxQueuedFrames()
                || !session.reserveInbound(charge)) {
            abort(NetErrorCode.INBOUND_OVERFLOW, null);
            return;
        }
        frames.add(new Pending(frame, charge));
        dispatch();
    }
    synchronized void close() {
        closed = true;
        discardPending();
        dispatch();
    }
    private void dispatch() {
        if (busy) return;
        boolean opening = !opened && !closed;
        boolean closing = closed && opened;
        if (!opening && !closing && (closed || frames.isEmpty())) {
            if (closed) releaseLifecycle();
            return;
        }
        if (!opening && !closing) active = frames.remove();
        ProtocolFrame frame = active == null ? null : active.frame();
        busy = true;
        if (opening) opened = true;
        if (closing) opened = false;
        try {
            session.executor().execute(() -> invoke(opening, closing, frame));
        } catch (RuntimeException failure) {
            busy = false;
            closed = true;
            opened = false;
            discardPending();
            releaseActive();
            releaseLifecycle();
            session.closeWithFailure(NetErrorCode.HANDLER_FAILED, failure);
        }
    }
    private void invoke(final boolean opening, final boolean closing, final ProtocolFrame frame) {
        if (session.inIoThread()) {
            completed(!opening && !closing, null, ZeroException.of(NetErrorCode.HANDLER_FAILED,
                    "KCP business executor must not inline on IO thread", null));
            return;
        }
        try {
            if (opening) session.listener().onOpen(session.connection());
            else if (closing) session.listener().onClose(session.connection());
            else {
                if (session.closed() || !session.authorized()) {
                    completed(true, null, KcpServer.error(NetErrorCode.AUTHENTICATION_EXPIRED, null));
                    return;
                }
                CompletionStage<List<ProtocolFrame>> result = session.handler().handle(session.connection(), frame);
                result.whenComplete((responses, cause) -> completed(true, responses, cause));
                return;
            }
            completed(false, List.of(), null);
        } catch (RuntimeException failure) {
            completed(!opening && !closing, null, failure);
        }
    }
    private synchronized void completed(final boolean handled, final List<ProtocolFrame> responses, final Throwable failure) {
        busy = false;
        if (handled) releaseActive();
        if (failure != null) {
            abort(NetErrorCode.HANDLER_FAILED, failure);
        } else if (!closed) sendResponses(responses);
        dispatch();
    }
    private void sendResponses(final List<ProtocolFrame> responses) {
        if (responses == null || responses.size() > session.options().maxQueuedFrames()) {
            abort(NetErrorCode.HANDLER_FAILED, null);
            return;
        }
        for (ProtocolFrame frame : responses) {
            session.respond(frame).whenComplete((ignored, failure) -> {
                if (failure != null) session.closeWithFailure(NetErrorCode.SEND_FAILED, failure);
            });
        }
    }
    private void abort(final NetErrorCode code, final Throwable failure) {
        closed = true;
        discardPending();
        session.closeWithFailure(code, failure);
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
