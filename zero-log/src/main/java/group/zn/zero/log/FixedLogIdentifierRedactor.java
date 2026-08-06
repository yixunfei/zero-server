package group.zn.zero.log;

/**
 * 把标识替换为固定文本的默认脱敏器。
 *
 * @author zn
 */
public final class FixedLogIdentifierRedactor implements LogIdentifierRedactor {

    /**
     * 固定脱敏文本。
     */
    public static final String REDACTED = "[REDACTED]";

    /**
     * 无状态单例。
     */
    private static final FixedLogIdentifierRedactor INSTANCE = new FixedLogIdentifierRedactor();

    /**
     * 单例类不允许外部实例化。
     */
    private FixedLogIdentifierRedactor() {
    }

    /**
     * 返回固定脱敏器单例。
     *
     * @return 无状态、线程安全的单例；不可为空。
     */
    public static FixedLogIdentifierRedactor instance() {
        return INSTANCE;
    }

    /**
     * 返回固定脱敏文本。
     *
     * @param domain 稳定字段域；不可为空或非法。
     * @param identifier 原始标识；不可为空。
     * @return 固定值 {@value #REDACTED}；不可为空；线程安全。
     * @throws group.zn.zero.core.error.ZeroException 字段域或原始标识非法时抛出。
     */
    @Override
    public String redact(final String domain, final String identifier) {
        LogRedactionSupport.requireDomain(domain);
        LogRedactionSupport.requireIdentifier(identifier);
        return REDACTED;
    }
}
