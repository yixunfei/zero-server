package group.zn.zero.benchmark.performance;

import group.zn.zero.benchmark.loadgenerated.dto.LoadSubmitProtocolDTO;
import group.zn.zero.benchmark.loadgenerated.dto.codec.LoadSubmitProtocolDTOCodec;
import group.zn.zero.benchmark.loadgenerated.protocol.ProtocolIds;
import group.zn.zero.benchmark.loadgenerated.protocol.dispatch.GeneratedProtocolDispatcher;
import group.zn.zero.protocol.ProtocolFrame;
import group.zn.zero.protocol.buffer.ZeroWriter;

/** 真实 codegen 产物的负载接入；BO 只处理 DTO，临时请求不会跨线程共享。 @author zn */
public final class GeneratedLoadPayload implements LoadPayload {
    /** 每个 Actor 执行线程的已注册分发器，调用结束清理响应引用。 */
    private final ThreadLocal<Business> business = ThreadLocal.withInitial(Business::new);

    /** 公共无参构造供显式负载工具加载；不启动线程。 */
    public GeneratedLoadPayload() { }

    /** 创建真实生成 DTO 并编码，计入客户端负载。 */
    @Override public ProtocolFrame request(final long sequence, final long due, final int size) {
        var request = new LoadSubmitProtocolDTO();
        request.sequence = sequence;
        request.due = due;
        request.text = "消息" + "x".repeat(Math.max(0, size - 22));
        return frame(request);
    }

    /** @return 解码完整响应 DTO 后取得的关联序号。 */
    @Override public long sequence(final ProtocolFrame response) {
        return LoadSubmitProtocolDTOCodec.INSTANCE.read(response.payloadReader()).sequence;
    }

    /** 真正执行 generated dispatcher/BO 并重新编码响应；每个调用独立所有权。 */
    @Override public ProtocolFrame response(final ProtocolFrame request) {
        Business current = business.get();
        try {
            if (!current.dispatcher.dispatchFrame(request)) throw new IllegalArgumentException("unknown load protocol");
            return java.util.Objects.requireNonNull(current.response, "business response");
        } finally { current.response = null; }
    }

    private static ProtocolFrame frame(final LoadSubmitProtocolDTO message) {
        var writer = new ZeroWriter();
        LoadSubmitProtocolDTOCodec.INSTANCE.write(writer, message);
        return new ProtocolFrame(ProtocolIds.LOAD_SUBMIT_PROTOCOL, 1, 0, null, writer.toByteArray());
    }

    /** Actor 线程独占的 BO 分发状态，响应仅在本次调用栈内暂存。 @author zn */
    private static final class Business {
        /** 已注册生成处理器。 */
        private final GeneratedProtocolDispatcher dispatcher = new GeneratedProtocolDispatcher();
        /** 本次 BO 产出的响应。 */
        private ProtocolFrame response;
        private Business() { dispatcher.registerLoadSubmitEventBO(request -> response = frame(request)); }
    }
}
