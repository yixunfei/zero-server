package group.zn.zero.npc;

import group.zn.zero.core.error.ErrorCategory;
import group.zn.zero.core.error.ErrorCode;

/** 本地 NPC 生命周期失败码，调用方可区分非法状态、重复与缺失。 @author zn */
public enum NpcErrorCode implements ErrorCode {
    /** spawn 的 NPC 不是 CREATED。 */
    INVALID_STATE("ZERO-NPC-INVALID-STATE", "npc must be created"),
    /** 相同身份已经存在。 */
    ALREADY_EXISTS("ZERO-NPC-ALREADY-EXISTS", "npc already exists"),
    /** 指定 NPC 不存在，不修改状态。 */
    NOT_FOUND("ZERO-NPC-NOT-FOUND", "npc not found");

    /** 稳定错误码。 */
    private final String code;
    /** 安全错误消息。 */
    private final String message;
    NpcErrorCode(final String code, final String message) { this.code = code; this.message = message; }
    /** @return 客户端请求错误分类；线程安全。 */
    @Override public ErrorCategory category() { return ErrorCategory.CLIENT_REQUEST; }
    /** @return 不可为空的错误码；线程安全。 */
    @Override public String code() { return code; }
    /** @return 不可为空的安全消息；线程安全。 */
    @Override public String message() { return message; }
}
