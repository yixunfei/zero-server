package group.zn.zero.npc;

import group.zn.zero.core.error.ZeroException;
import java.util.Objects;

/**
 * 提交后的 NPC 事件；观察者不得修改异常的 suppressed 列表。
 * @param zoneId 区域身份。
 * @param npcId NPC 身份。
 * @param type 事件类型。
 * @param version 当前快照版本。
 * @param traceId 当前命令链路标识。
 * @param failure 仅 BEHAVIOR_FAILED 非空，包含错误码与原始原因；供进程内诊断使用。
 * @author zn
 */
public record NpcEvent(String zoneId, NpcId npcId, Type type, long version, String traceId, ZeroException failure) {
    /** 校验事件与失败信息的一致性；无状态修改。
     * @throws NullPointerException 必填标识为空。
     * @throws IllegalArgumentException 失败事件与异常字段不一致。 */
    public NpcEvent {
        Objects.requireNonNull(zoneId, "zoneId");
        Objects.requireNonNull(npcId, "npcId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(traceId, "traceId");
        if ((type == Type.BEHAVIOR_FAILED) != (failure != null)) {
            throw new IllegalArgumentException("only BEHAVIOR_FAILED requires a failure");
        }
    }

    /** NPC 生命周期及行为观察类型。 @author zn */
    public enum Type {
        /** 已生成。 */ SPAWNED,
        /** 已移除。 */ DESPAWNED,
        /** 行为状态已改变。 */ BEHAVIOR_CHANGED,
        /** 决策失败并已隔离。 */ BEHAVIOR_FAILED,
        /** 预算不足跳过。 */ TICK_SKIPPED
    }
}
