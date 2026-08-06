package group.zn.zero.log;

/**
 * 日志标识安全引用生成器。
 *
 * <p>该安全边界只允许框架提供的固定脱敏和带部署密钥的 HMAC-SHA-256 两种实现，业务代码不能
 * 注入任意函数放宽默认脱敏。实现线程安全、确定且有界；每个传入值都必须作为不可信原始标识
 * 重新处理，不能仅凭安全引用的字符串外形跳过脱敏。实现不得记录或暴露原始标识，也不得使用
 * 无盐摘要代替带密钥 HMAC。
 *
 * @author zn
 */
public sealed interface LogIdentifierRedactor
        permits FixedLogIdentifierRedactor, HmacSha256LogIdentifierRedactor {

    /**
     * 为指定字段域生成安全引用。
     *
     * @param domain 稳定字段域；不可为空或空白，最长 128 字符且不得包含控制字符。
     * @param identifier 原始标识；不可为空。
     * @return 安全引用；不可为空；不得包含控制字符。
     * @throws group.zn.zero.core.error.ZeroException 参数或脱敏过程非法时抛出，并绑定
     *         {@link LogErrorCode#SENSITIVE_FIELD_REJECTED}。
     */
    String redact(String domain, String identifier);
}
