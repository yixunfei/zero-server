package group.zn.zero.examples.centerlogic.kafka.common;

/** Registration metadata shared by center and logic processes. */
public record CenterRegistration(String instanceId, String serviceName, String traceId) {
    public CenterRegistration {
        if (instanceId == null || instanceId.isBlank() || serviceName == null || serviceName.isBlank()
                || traceId == null || traceId.isBlank()) {
            throw new IllegalArgumentException("registration fields must not be blank");
        }
    }
}
