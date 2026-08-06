package group.zn.zero.log;

/**
 * 敏感字段策略动作，枚举顺序由宽松到严格。
 *
 * @author zn
 */
public enum SensitiveFieldAction {

    /**
     * 保留字段内容，仅执行控制字符转义。
     */
    ALLOW,

    /**
     * 使用已注入的 {@link LogIdentifierRedactor} 替换字段内容。
     */
    REDACT,

    /**
     * 拒绝整条日志记录。
     */
    REJECT
}
