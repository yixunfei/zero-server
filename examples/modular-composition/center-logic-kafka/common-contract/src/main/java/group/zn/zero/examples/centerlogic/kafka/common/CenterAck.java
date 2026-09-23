package group.zn.zero.examples.centerlogic.kafka.common;

/** Non-sensitive acknowledgement metadata returned by center. */
public record CenterAck(String instanceId, String state, String traceId) {
}
