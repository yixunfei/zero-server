package group.zn.zero.log;

/**
 * 日志所描述操作的受控结果。
 *
 * @author zn
 */
public enum LogResult {

    /**
     * 操作已开始，尚无最终结果。
     */
    STARTED,

    /**
     * 操作成功。
     */
    SUCCESS,

    /**
     * 操作失败。
     */
    FAILURE,

    /**
     * 操作被安全或业务规则拒绝。
     */
    REJECTED,

    /**
     * 操作超时。
     */
    TIMEOUT,

    /**
     * 操作以降级方式完成。
     */
    DEGRADED
}
