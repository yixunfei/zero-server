package group.zn.zero.event.bus;

import group.zn.zero.core.error.ErrorCategory;
import group.zn.zero.core.error.ErrorCode;

/** 事件发布策略拒绝与运行时故障分开统计。 @author zn */
public enum EventErrorCode implements ErrorCode {
    /** 拦截器基于业务策略拒绝发布。 */
    PUBLICATION_REJECTED;

    /** @return 服务调用类错误；不可为空，线程安全。 */
    @Override public ErrorCategory category() { return ErrorCategory.SERVER_CALL; }
    /** @return 稳定错误码；不可为空，线程安全。 */
    @Override public String code() { return "ZERO-EVENT-PUBLICATION-REJECTED"; }
    /** @return 固定安全说明；不可为空，线程安全。 */
    @Override public String message() { return "event publication rejected by interceptor"; }
}
