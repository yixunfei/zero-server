package group.zn.zero.log;

import group.zn.zero.core.error.ErrorCode;

/**
 * 日志操作和结果值对象。
 *
 * @param name 稳定操作名。
 * @param result 操作结果。
 * @param errorCode 失败错误码；开始和成功结果必须为空。
 * @author zn
 */
public record LogOperation(String name, LogResult result, ErrorCode errorCode) {

    /**
     * 创建日志操作。
     *
     * <p>该值对象不可变、线程安全；依赖日志等级和类型的 ErrorCode 最终组合由
     * {@link ZeroLogRecord} 校验。
     *
     * @throws group.zn.zero.core.error.ZeroException 操作名、结果或错误码结构非法时抛出，并绑定
     *         {@link LogErrorCode#INVALID_RECORD}。
     */
    public LogOperation {
        name = LogRecordValidator.normalizeIdentifier(name, "operation");
        LogRecordValidator.requireResult(result);
        LogRecordValidator.requireErrorCodeShape(errorCode);
    }
}
