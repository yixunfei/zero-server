package group.zn.zero.rpc.common;

import group.zn.zero.core.error.ErrorCode;
import group.zn.zero.core.error.SystemErrorCode;
import group.zn.zero.core.error.ZeroException;
import java.util.Objects;
import java.util.Optional;

/**
 * RPC 标准返回。
 *
 * @param <T> 业务结果类型。
 * @author zn
 */
public final class RpcResult<T> {

    /**
     * 是否成功。
     */
    private final boolean success;

    /**
     * 错误码。
     */
    private final ErrorCode errorCode;

    /**
     * 错误说明。
     */
    private final String errorMsg;

    /**
     * 业务结果。
     */
    private final T result;

    /**
     * 远端异常类名。
     */
    private final String exceptionClass;

    /**
     * 链路追踪 ID。
     */
    private final String traceId;

    /**
     * 关联 ID。
     */
    private final String correlationId;

    /**
     * 远端服务名。
     */
    private final String remoteService;

    /**
     * 远端方法名。
     */
    private final String remoteMethod;

    private RpcResult(
            final boolean success,
            final ErrorCode errorCode,
            final String errorMsg,
            final T result,
            final String exceptionClass,
            final String traceId,
            final String correlationId,
            final String remoteService,
            final String remoteMethod) {
        this.success = success;
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode");
        this.errorMsg = errorMsg == null ? "" : errorMsg;
        this.result = result;
        this.exceptionClass = exceptionClass == null ? "" : exceptionClass;
        this.traceId = traceId == null ? "" : traceId;
        this.correlationId = correlationId == null ? "" : correlationId;
        this.remoteService = remoteService == null ? "" : remoteService;
        this.remoteMethod = remoteMethod == null ? "" : remoteMethod;
    }

    /**
     * 创建成功结果。
     *
     * @param result 业务结果；可为空。
     * @param <T> 业务结果类型。
     * @return 成功 RPC 结果；不可为空；线程安全。
     */
    public static <T> RpcResult<T> success(final T result) {
        return new RpcResult<>(true, SystemErrorCode.OK, "", result, "", "", "", "", "");
    }

    /**
     * 创建失败结果。
     *
     * @param errorCode 错误码；不可为空。
     * @param errorMsg 错误说明；可为空。
     * @param <T> 业务结果类型。
     * @return 失败 RPC 结果；不可为空；线程安全。
     * @throws NullPointerException 当错误码为空时抛出。
     */
    public static <T> RpcResult<T> failure(final ErrorCode errorCode, final String errorMsg) {
        ErrorCode current = Objects.requireNonNull(errorCode, "errorCode");
        return new RpcResult<>(false, current, errorMsg, null, "", "", "", "", "");
    }

    /**
     * 创建带远端上下文的副本。
     *
     * @param traceId 链路追踪 ID；可为空。
     * @param correlationId 关联 ID；可为空。
     * @param remoteService 远端服务名；可为空。
     * @param remoteMethod 远端方法名；可为空。
     * @return RPC 结果副本；不可为空；线程安全。
     */
    public RpcResult<T> withRemoteContext(
            final String traceId,
            final String correlationId,
            final String remoteService,
            final String remoteMethod) {
        return new RpcResult<>(
                success,
                errorCode,
                errorMsg,
                result,
                exceptionClass,
                traceId,
                correlationId,
                remoteService,
                remoteMethod);
    }

    /**
     * 创建带异常类名的副本。
     *
     * @param exceptionClass 远端异常类名；可为空。
     * @return RPC 结果副本；不可为空；线程安全。
     */
    public RpcResult<T> withExceptionClass(final String exceptionClass) {
        return new RpcResult<>(
                success,
                errorCode,
                errorMsg,
                result,
                exceptionClass,
                traceId,
                correlationId,
                remoteService,
                remoteMethod);
    }

    /**
     * 返回是否成功。
     *
     * @return true 表示成功；线程安全。
     */
    public boolean success() {
        return success;
    }

    /**
     * 返回业务结果。
     *
     * @return 业务结果；可能为空；线程安全。
     */
    public T result() {
        return result;
    }

    /**
     * 成功时返回业务结果，失败时抛出统一异常。
     *
     * @return 业务结果；可能为空；线程安全。
     * @throws ZeroException 当 RPC 失败时抛出，异常绑定当前错误码。
     */
    public T orThrow() {
        if (!success) {
            throw ZeroException.of(errorCode, errorMsg.isBlank() ? errorCode.message() : errorMsg, null);
        }
        return result;
    }

    /**
     * 返回可选业务结果。
     *
     * @return 不可变、有序无关、可能为空的 Optional；线程安全。
     */
    public Optional<T> optional() {
        return Optional.ofNullable(result);
    }

    /**
     * 返回错误码。
     *
     * @return 错误码；不可为空；线程安全。
     */
    public ErrorCode errorCode() {
        return errorCode;
    }

    /**
     * 返回错误说明。
     *
     * @return 错误说明；不可为空；线程安全。
     */
    public String errorMsg() {
        return errorMsg;
    }

    /**
     * 返回远端异常类名。
     *
     * @return 远端异常类名；不可为空；线程安全。
     */
    public String exceptionClass() {
        return exceptionClass;
    }

    /**
     * 返回链路追踪 ID。
     *
     * @return 链路追踪 ID；不可为空；线程安全。
     */
    public String traceId() {
        return traceId;
    }

    /**
     * 返回关联 ID。
     *
     * @return 关联 ID；不可为空；线程安全。
     */
    public String correlationId() {
        return correlationId;
    }

    /**
     * 返回远端服务名。
     *
     * @return 远端服务名；不可为空；线程安全。
     */
    public String remoteService() {
        return remoteService;
    }

    /**
     * 返回远端方法名。
     *
     * @return 远端方法名；不可为空；线程安全。
     */
    public String remoteMethod() {
        return remoteMethod;
    }
}
