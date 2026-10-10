package group.zn.zero.logic.session;

import group.zn.zero.core.error.ErrorCategory;
import group.zn.zero.core.error.ErrorCode;

/** 业务会话身份、生命周期与属性约束的稳定失败码。 @author zn */
public enum LogicSessionErrorCode implements ErrorCode {
    /** 同一连接 ID 已绑定不同对象。 */
    CONNECTION_CONFLICT("ZERO-LOGIC-CONNECTION-CONFLICT", "connection identity conflict"),
    /** 会话目录已满。 */
    CAPACITY_EXCEEDED("ZERO-LOGIC-CAPACITY-EXCEEDED", "logic session capacity exceeded"),
    /** 关闭会话禁止继续写入。 */
    SESSION_CLOSED("ZERO-LOGIC-SESSION-CLOSED", "logic session closed"),
    /** 同名属性的活跃类型不匹配。 */
    ATTRIBUTE_TYPE_CONFLICT("ZERO-LOGIC-ATTRIBUTE-TYPE-CONFLICT", "logic attribute type conflict");
    /** 稳定代码。 */
    private final String code;
    /** 安全消息。 */
    private final String message;
    LogicSessionErrorCode(final String code, final String message) { this.code = code; this.message = message; }
    /** @return 请求错误分类；线程安全。 */
    @Override public ErrorCategory category() { return ErrorCategory.CLIENT_REQUEST; }
    /** @return 稳定错误码；不可为空，线程安全。 */
    @Override public String code() { return code; }
    /** @return 安全说明；不可为空，线程安全。 */
    @Override public String message() { return message; }
}
