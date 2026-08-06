package group.zn.zero.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import group.zn.zero.actor.LaneKey;
import group.zn.zero.log.LogType;
import group.zn.zero.protocol.ProtocolDirection;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 本地逻辑示例测试。
 *
 * @author zn
 */
class LocalLogicExampleTest {

    /**
     * 验证本地逻辑示例可以串联事件、协议注册和 Actor 调度。
     */
    @Test
    void playerQueryShouldRunThroughLocalLogicFlow() {
        LocalLogicExample example = new LocalLogicExample();

        LogicFlowResult result = example.runPlayerQuery("1001", "event-logic-1", "trace-logic-1");

        assertEquals("ZeroLogicQueryPlayerProtocol", result.protocolName());
        assertEquals("event-logic-1", result.eventId());
        assertEquals("trace-logic-1", result.traceId());
        assertEquals(LaneKey.player("1001"), result.laneKey());
        assertEquals(List.of(
                "interceptor:trace-logic-1",
                "event:62001",
                "actor:ZeroLogicQueryPlayerProtocol:1001"), result.steps());
    }

    /**
     * 验证逻辑示例暴露只读协议定义。
     */
    @Test
    void protocolDefinitionShouldDescribeExampleProtocol() {
        LocalLogicExample example = new LocalLogicExample();

        assertEquals(62001, example.protocolDefinition().id());
        assertEquals("ZeroLogicQueryPlayerProtocol", example.protocolDefinition().name());
        assertEquals(ProtocolDirection.CLIENT_TO_SERVER, example.protocolDefinition().direction());
    }

    /**
     * 验证流程结果的步骤列表不可被外部修改。
     */
    @Test
    void flowResultStepsShouldBeImmutable() {
        LocalLogicExample example = new LocalLogicExample();

        LogicFlowResult result = example.runPlayerQuery("1001", "event-logic-2", "trace-logic-2");

        assertThrows(UnsupportedOperationException.class, () -> result.steps().add("changed"));
    }

    /**
     * 验证观测示例输出日志、指标和 TraceId。
     */
    @Test
    void observedFlowShouldExportLogsAndMetrics() {
        LocalLogicExample example = new LocalLogicExample();

        ObservedLogicFlowResult result = example.runObservedPlayerQuery(
                "1001",
                "event-observed-1",
                "trace-observed-1");

        assertEquals("trace-observed-1", result.flowResult().traceId());
        assertEquals(1, result.logs().size());
        assertEquals(LogType.BUSINESS, result.logs().getFirst().logType());
        assertEquals("trace-observed-1", result.logs().getFirst().traceId());
        assertEquals("local-memory", result.logs().getFirst().fields().get("route"));
        assertEquals(1, result.metrics().size());
        assertEquals("zero_logic_flow_total", result.metrics().getFirst().name());
        assertEquals("zero-logic", result.metrics().getFirst().labels().get("module"));
        assertEquals("player-query", result.metrics().getFirst().labels().get("operation"));
        assertFalse(result.metrics().getFirst().labels().containsKey("traceId"));
        assertFalse(result.metrics().getFirst().labels().containsKey("playerId"));
        assertEquals(true, result.metricText().contains("zero_logic_flow_total"));
    }
}
