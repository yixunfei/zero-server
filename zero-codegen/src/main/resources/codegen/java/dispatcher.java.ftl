/*
 * ${generatedMarker}. Do not edit manually.
 */
package ${packageName};

<#list imports as import>
import ${import};
</#list>

/**
 * 生成协议事件分发器。
 * 注册操作线程不安全：在启动阶段完成注册并安全发布，或由调用方串行化注册和分发。
 * 注册完成后可并发分发，业务状态的线程安全性由 BO 及其执行域保证。
 */
public final class GeneratedProtocolDispatcher {

    /**
     * 协议处理器表。
     */
    private final Map<Integer, Handler> handlers = new HashMap<>();
<#list registrations as registration>

    /**
     * ${registration.boName} 业务接口。
     */
    private ${registration.boName} ${registration.fieldName};
</#list>
<#list registrations as registration>

    /**
     * 注册 ${registration.boName}。
     *
     * @param bo 业务接口；不可为空。
     * @throws NullPointerException 当业务接口为空时抛出。
     */
    public void ${registration.registerName}(final ${registration.boName} bo) {
        this.${registration.fieldName} = Objects.requireNonNull(bo, "bo");
<#list registration.handlers as handler>
        handlers.put(ProtocolIds.${handler.protocolConstant}, payload -> {
            ${handler.requestMessage} request = ${handler.codecName}.INSTANCE.read(payload);
            requireFullyRead(payload);
            ${registration.fieldName}.${handler.methodName}(${handler.arguments});
        });
</#list>
    }
</#list>

    /**
     * 分发协议 payload。
     *
     * @param protocolId 协议号。
     * @param payload payload 字节；不可为空。
     * 调用期间数组必须保持稳定；同步读取后仅向 BO 传递自持有 DTO。
     * @return true 表示找到并执行处理器；false 表示没有匹配处理器。
     * @throws NullPointerException 当 payload 为空时抛出。
     * @throws ZeroException payload 畸形或存在对象外尾随数据时抛出，且不执行 BO。
     */
    public boolean dispatch(final int protocolId, final byte[] payload) {
        Objects.requireNonNull(payload, "payload");
        Handler handler = handlers.get(protocolId);
        if (handler == null) {
            return false;
        }
        handler.handle(ZeroReader.readOnly(payload));
        return true;
    }

    /**
     * 直接分发不可变帧；业务仍接收既有 DTO/参数，内容自持有且无需释放。
     * @param frame 协议帧，不可为空。
     * @return 是否找到处理器；变更业务状态，线程安全性由 BO 决定。
     * @throws NullPointerException 帧为空。
     * @throws ZeroException payload 畸形或存在对象外尾随数据时抛出，且不执行 BO。
     */
    public boolean dispatchFrame(final group.zn.zero.protocol.ProtocolFrame frame) {
        Objects.requireNonNull(frame, "frame");
        Handler handler = handlers.get(frame.protocolId());
        if (handler == null) {
            return false;
        }
        handler.handle(frame.payloadReader());
        return true;
    }

    /** 在业务产生副作用前验证完整消息；对象内扩展字段已由 codec 消费。 */
    private static void requireFullyRead(final ZeroReader payload) {
        if (payload.isReadable()) {
            throw ZeroException.of(ProtocolErrorCode.DECODE_FAILED,
                    "payload has trailing bytes: " + payload.readableBytes(), null);
        }
    }

    /**
     * 生成协议处理器。
     */
    @FunctionalInterface
    private interface Handler {

        /**
         * 处理 payload。
         *
         * @param payload 独占游标的只读读取器；不可为空。
         */
        void handle(ZeroReader payload);
    }
}
