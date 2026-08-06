package group.zn.zero.logic;

import group.zn.zero.actor.LaneKey;
import java.util.List;
import java.util.Objects;

/**
 * 本地逻辑示例流程结果。
 *
 * @param protocolName 协议名称。
 * @param eventId 事件标识。
 * @param traceId 链路追踪标识。
 * @param laneKey Actor lane 绑定键。
 * @param steps 执行步骤快照。
 * @author zn
 */
public record LogicFlowResult(
        String protocolName,
        String eventId,
        String traceId,
        LaneKey laneKey,
        List<String> steps) {

    /**
     * 创建逻辑流程结果。
     *
     * @throws NullPointerException 当协议名称、事件标识、traceId、lane key 或步骤列表为空时抛出。
     */
    public LogicFlowResult {
        Objects.requireNonNull(protocolName, "protocolName");
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(traceId, "traceId");
        Objects.requireNonNull(laneKey, "laneKey");
        Objects.requireNonNull(steps, "steps");
        steps = List.copyOf(steps);
    }

    /**
     * 返回执行步骤快照。
     *
     * @return 不可变、有序、可能为空、线程安全的步骤列表。
     */
    @Override
    public List<String> steps() {
        return steps;
    }
}
