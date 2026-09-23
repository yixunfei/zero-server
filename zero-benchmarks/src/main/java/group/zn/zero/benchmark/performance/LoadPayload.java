package group.zn.zero.benchmark.performance;

import group.zn.zero.protocol.ProtocolFrame;
import java.nio.ByteBuffer;

/** 显式负载工具的 payload 适配，生成 DTO fixture 仅编译到 target，不属于业务 SPI。 @author zn */
public interface LoadPayload {
    /** @return 新建请求帧；参数为序号、计划发送时刻和目标负载尺寸，线程安全。 */
    ProtocolFrame request(long sequence, long due, int size);
    /** @return 完整响应关联序号；只读、线程安全。 */
    long sequence(ProtocolFrame response);
    /** @return Actor/BO 处理后的响应帧；线程安全，生成 DTO 实现需实际解码/分发/编码。 */
    ProtocolFrame response(ProtocolFrame request);

    /** @return 根据显式测试参数加载的实现，默认 echo；初始化失败立即终止负载。 */
    static LoadPayload configured() {
        String fixture = System.getProperty("zero.load.payload");
        if (fixture == null) return Echo.INSTANCE;
        try { return Class.forName(fixture).asSubclass(LoadPayload.class).getConstructor().newInstance(); }
        catch (ReflectiveOperationException failure) { throw new IllegalStateException("load fixture unavailable", failure); }
    }

    /** 保留预制 Frame echo 作为协议隔离对照。 @author zn */
    enum Echo implements LoadPayload {
        /** 无状态共享实例。 */
        INSTANCE;
        /** 创建包含序号/时刻的自持有帧。 */
        @Override public ProtocolFrame request(final long sequence, final long due, final int size) {
            byte[] payload = new byte[size];
            ByteBuffer.wrap(payload).putLong(sequence).putLong(due);
            return new ProtocolFrame(1, 1, 0, null, payload);
        }
        /** @return 响应中的请求序号；只读。 */
        @Override public long sequence(final ProtocolFrame response) { return response.payloadView().getLong(); }
        /** @return 原自持有帧；只用于 echo 对照。 */
        @Override public ProtocolFrame response(final ProtocolFrame request) { return request; }
    }
}
