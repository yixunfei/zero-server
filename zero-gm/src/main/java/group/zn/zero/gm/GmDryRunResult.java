package group.zn.zero.gm;

import group.zn.zero.core.error.ErrorCode;
import group.zn.zero.core.error.SystemErrorCode;
import group.zn.zero.core.error.ZeroException;
import java.util.Map;
import java.util.Optional;

/**
 * GM dry-run 预演结果。
 *
 * @param commandKey 指令 key；不可为空。
 * @param accepted 预演是否允许进入正式执行前置条件。
 * @param message 预演说明；不可为空。
 * @param previewData 预演数据；不可为空；构造后不可变；无序；可能为空；线程安全。
 * @param errorCode 拒绝 ErrorCode；accepted 时为空，拒绝时不可为空。
 * @author zn
 */
public record GmDryRunResult(
        String commandKey,
        boolean accepted,
        String message,
        Map<String, String> previewData,
        ErrorCode errorCode) {

    /**
     * 创建 GM dry-run 预演结果。
     *
     * @throws ZeroException 当必要字段为空、成功携带 ErrorCode、拒绝缺少 ErrorCode 或数据快照非法时抛出。
     */
    public GmDryRunResult {
        if (commandKey == null || message == null || previewData == null) {
            throw invalidResult(null);
        }
        try {
            previewData = Map.copyOf(previewData);
        } catch (RuntimeException ex) {
            throw invalidResult(ex);
        }
        if (accepted == (errorCode != null)
                || errorCode != null && SystemErrorCode.OK.code().equals(errorCode.code())) {
            throw invalidResult(null);
        }
    }

    /**
     * 创建通过的 dry-run 预演结果。
     *
     * @param commandKey 指令 key；不可为空。
     * @param message 预演说明；不可为空。
     * @param previewData 预演数据；不可为空；无序；可能为空。
     * @return dry-run 预演结果；不可为空；无数据变更；线程安全。
     * @throws ZeroException 当指令 key、说明或预演数据非法时抛出，并绑定
     *         {@link GmErrorCode#COMMAND_RESULT_INVALID}。
     */
    public static GmDryRunResult accepted(
            final String commandKey,
            final String message,
            final Map<String, String> previewData) {
        return new GmDryRunResult(commandKey, true, message, previewData, null);
    }

    /**
     * 使用通用无副作用拒绝码创建未通过的 dry-run 结果。
     *
     * @param commandKey 指令 key；不可为空。
     * @param message 预演说明；不可为空。
     * @param previewData 预演数据；不可为空；无序；可能为空。
     * @return 拒绝结果；不可为空；无业务数据变更；线程安全。
     * @throws ZeroException 当必要字段或数据快照非法时抛出。
     */
    public static GmDryRunResult rejected(
            final String commandKey,
            final String message,
            final Map<String, String> previewData) {
        return rejected(commandKey, message, previewData, GmErrorCode.COMMAND_REJECTED);
    }

    /**
     * 使用明确领域 ErrorCode 创建未通过的 dry-run 结果。
     *
     * @param commandKey 指令 key；不可为空。
     * @param message 预演说明；不可为空。
     * @param previewData 预演数据；不可为空；无序；可能为空。
     * @param errorCode 真实拒绝 ErrorCode；不可为空。
     * @return 拒绝结果；不可为空；无业务数据变更；线程安全。
     * @throws ZeroException 当必要字段、ErrorCode 或数据快照非法时抛出。
     */
    public static GmDryRunResult rejected(
            final String commandKey,
            final String message,
            final Map<String, String> previewData,
            final ErrorCode errorCode) {
        return new GmDryRunResult(commandKey, false, message, previewData, errorCode);
    }

    /**
     * 返回可选拒绝 ErrorCode。
     *
     * @return 拒绝时包含真实 ErrorCode，accepted 时为空；不可为空；线程安全。
     */
    public Optional<ErrorCode> optionalErrorCode() {
        return Optional.ofNullable(errorCode);
    }

    private static ZeroException invalidResult(final RuntimeException cause) {
        if (cause == null) {
            return ZeroException.of(GmErrorCode.COMMAND_RESULT_INVALID);
        }
        return ZeroException.of(
                GmErrorCode.COMMAND_RESULT_INVALID,
                GmErrorCode.COMMAND_RESULT_INVALID.message(),
                cause);
    }
}
