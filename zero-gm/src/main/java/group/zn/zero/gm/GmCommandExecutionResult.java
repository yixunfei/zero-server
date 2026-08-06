package group.zn.zero.gm;

import group.zn.zero.core.error.ErrorCode;
import group.zn.zero.core.error.SystemErrorCode;
import group.zn.zero.core.error.ZeroException;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * GM 指令执行结果。
 *
 * @param commandKey 指令 key；不可为空。
 * @param dryRun 是否为 dry-run 结果。
 * @param success 是否成功。
 * @param message 执行说明；不可为空。
 * @param data 执行数据；不可为空；构造后不可变；无序；可能为空；线程安全。
 * @param errorCode 失败 ErrorCode；成功时为空，拒绝或失败时不可为空。
 * @author zn
 */
public record GmCommandExecutionResult(
        String commandKey,
        boolean dryRun,
        boolean success,
        String message,
        Map<String, String> data,
        ErrorCode errorCode) {

    /**
     * 创建 GM 指令执行结果。
     *
     * @throws ZeroException 当必要字段为空、成功携带 ErrorCode、失败缺少 ErrorCode 或数据快照非法时抛出。
     */
    public GmCommandExecutionResult {
        if (commandKey == null || message == null || data == null) {
            throw invalidResult(null);
        }
        try {
            data = Map.copyOf(data);
        } catch (RuntimeException ex) {
            throw invalidResult(ex);
        }
        if (success == (errorCode != null)
                || errorCode != null && SystemErrorCode.OK.code().equals(errorCode.code())) {
            throw invalidResult(null);
        }
    }

    /**
     * 创建正式执行成功结果。
     *
     * @param commandKey 指令 key；不可为空。
     * @param message 执行说明；不可为空。
     * @param data 执行数据；不可为空；无序；可能为空。
     * @return 执行结果；不可为空；无额外数据变更；线程安全。
     * @throws ZeroException 当指令 key、说明或执行数据非法时抛出，并绑定
     *         {@link GmErrorCode#COMMAND_RESULT_INVALID}。
     */
    public static GmCommandExecutionResult success(
            final String commandKey,
            final String message,
            final Map<String, String> data) {
        return new GmCommandExecutionResult(commandKey, false, true, message, data, null);
    }

    /**
     * 使用通用无副作用拒绝码创建正式执行拒绝结果。
     *
     * <p>handler 只有在能够证明没有产生业务副作用时才能返回该结果。</p>
     *
     * @param commandKey 指令 key；不可为空。
     * @param message 执行说明；不可为空。
     * @param data 执行数据；不可为空；无序；可能为空。
     * @return 拒绝结果；不可为空；线程安全。
     * @throws ZeroException 当必要字段或数据快照非法时抛出。
     */
    public static GmCommandExecutionResult rejected(
            final String commandKey,
            final String message,
            final Map<String, String> data) {
        return rejected(commandKey, message, data, GmErrorCode.COMMAND_REJECTED);
    }

    /**
     * 使用明确领域 ErrorCode 创建正式执行拒绝结果。
     *
     * <p>handler 只有在能够证明没有产生业务副作用时才能返回该结果；执行器会将提交状态记录为
     * {@link GmBusinessCommitState#NOT_COMMITTED}。</p>
     *
     * @param commandKey 指令 key；不可为空。
     * @param message 执行说明；不可为空。
     * @param data 执行数据；不可为空；无序；可能为空。
     * @param errorCode 真实拒绝 ErrorCode；不可为空。
     * @return 拒绝结果；不可为空；线程安全。
     * @throws ZeroException 当必要字段、ErrorCode 或数据快照非法时抛出。
     */
    public static GmCommandExecutionResult rejected(
            final String commandKey,
            final String message,
            final Map<String, String> data,
            final ErrorCode errorCode) {
        return new GmCommandExecutionResult(commandKey, false, false, message, data, errorCode);
    }

    /**
     * 从 dry-run 预演结果创建统一执行结果。
     *
     * @param dryRunResult dry-run 预演结果；不可为空。
     * @return 执行结果；不可为空；无额外数据变更；线程安全。
     * @throws NullPointerException 当 dry-run 结果为空时抛出。
     */
    public static GmCommandExecutionResult fromDryRun(final GmDryRunResult dryRunResult) {
        Objects.requireNonNull(dryRunResult, "dryRunResult");
        return new GmCommandExecutionResult(
                dryRunResult.commandKey(),
                true,
                dryRunResult.accepted(),
                dryRunResult.message(),
                dryRunResult.previewData(),
                dryRunResult.errorCode());
    }

    /**
     * 返回可选失败 ErrorCode。
     *
     * @return 拒绝或失败时包含真实 ErrorCode，成功时为空；不可为空；线程安全。
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
