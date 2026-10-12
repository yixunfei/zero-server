package group.zn.zero.examples.multiplayer;

import group.zn.zero.core.error.ZeroException;

/** 保留 ErrorCode 的示例业务异常，可由统一 RPC 边界识别。 @author zn */
public final class MultiServerError extends ZeroException {
    /**
     * 创建含默认诊断的异常；不修改外部状态。
     * @param code 非空示例错误码。
     * @throws NullPointerException code 为空。
     */
    public MultiServerError(final MultiServerErrorCode code) { super(code, code.message()); }

    /**
     * 创建含字段诊断的异常；不修改外部状态。
     * @param code 非空示例错误码。
     * @param message 非空诊断信息。
     * @throws NullPointerException 任一参数为空。
     */
    public MultiServerError(final MultiServerErrorCode code, final String message) { super(code, message); }
}
