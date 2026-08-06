/*
 * ${generatedMarker}. Do not edit manually.
 */
package ${packageName};

<#list imports as import>
import ${import};
</#list>

/**
 * 生成协议事件分发器。
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
            ${handler.requestMessage} request = ${handler.codecName}.INSTANCE.read(new ZeroReader(payload));
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
     * @return true 表示找到并执行处理器；false 表示没有匹配处理器。
     * @throws NullPointerException 当 payload 为空时抛出。
     */
    public boolean dispatch(final int protocolId, final byte[] payload) {
        Objects.requireNonNull(payload, "payload");
        Handler handler = handlers.get(protocolId);
        if (handler == null) {
            return false;
        }
        handler.handle(payload);
        return true;
    }

    /**
     * 生成协议处理器。
     */
    @FunctionalInterface
    private interface Handler {

        /**
         * 处理 payload。
         *
         * @param payload payload 字节；不可为空。
         */
        void handle(byte[] payload);
    }
}
