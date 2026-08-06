package group.zn.zero.log;

/**
 * 日志来源值对象。
 *
 * @param serviceName 服务名。
 * @param instanceId 实例标识。
 * @param module 模块名。
 * @author zn
 */
public record LogSource(String serviceName, String instanceId, String module) {

    /**
     * 创建日志来源并规范化标识两端空白。
     *
     * <p>该值对象不可变、线程安全，不执行 IO，也不读取线程上下文。
     *
     * @throws group.zn.zero.core.error.ZeroException 任一标识为空、超过 128 字符或包含控制字符时
     *         抛出，并绑定 {@link LogErrorCode#INVALID_RECORD}。
     */
    public LogSource {
        serviceName = LogRecordValidator.normalizeIdentifier(serviceName, "serviceName");
        instanceId = LogRecordValidator.normalizeIdentifier(instanceId, "instanceId");
        module = LogRecordValidator.normalizeIdentifier(module, "module");
    }
}
