package group.zn.zero.ownership;

import java.util.Objects;

/** 进程实例身份；同一节点重启必须生成新的 incarnationId。 */
public record ProcessIncarnation(String processId, String incarnationId) {
    /** 校验进程身份。 */
    public ProcessIncarnation {
        requireText(processId, "processId");
        requireText(incarnationId, "incarnationId");
    }

    private static void requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
