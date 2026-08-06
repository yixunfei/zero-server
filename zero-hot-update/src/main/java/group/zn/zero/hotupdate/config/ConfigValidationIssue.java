package group.zn.zero.hotupdate.config;

import java.util.Objects;

/**
 * 配置表业务校验问题。
 *
 * @param code 业务校验问题代码；不可为空白，应保持低基数。
 * @param message 仅面向开发者的简短说明；不可为空白，不应包含完整 CSV 行或敏感配置值。
 * @author zn
 */
public record ConfigValidationIssue(String code, String message) {

    /**
     * 校验问题标准化构造器。
     *
     * @throws NullPointerException 当代码或说明为空时抛出。
     * @throws IllegalArgumentException 当代码或说明为空白时抛出。
     */
    public ConfigValidationIssue {
        code = requireText(code, "code");
        message = requireText(message, "message");
    }

    private static String requireText(final String value, final String name) {
        String checked = Objects.requireNonNull(value, name);
        if (checked.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return checked;
    }
}
