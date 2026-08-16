package group.zn.zero.runtime.internal;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 运行时公开标识符的统一校验规则。
 *
 * @author zn
 */
public final class RuntimeIdentifiers {

    /** 组件、能力和来源 ID 的稳定格式。 */
    private static final Pattern STABLE_ID =
            Pattern.compile("[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*");

    /** 配置来源 alias 的安全格式，兼容环境变量大写命名。 */
    private static final Pattern SAFE_ALIAS =
            Pattern.compile("[A-Za-z][A-Za-z0-9_.-]*");

    private RuntimeIdentifiers() {
    }

    /**
     * 校验稳定、可打印的逻辑 ID。
     *
     * @param value 候选值；不可为空。
     * @param label 参数标签；不可为空。
     * @return 原值；不可为空。
     * @throws IllegalArgumentException 格式非法时抛出，不回显候选值。
     */
    public static String requireStableId(final String value, final String label) {
        String checked = Objects.requireNonNull(value, label).trim();
        if (!STABLE_ID.matcher(checked).matches()) {
            throw new IllegalArgumentException(label + " must be a stable lowercase identifier");
        }
        return checked;
    }

    /**
     * 校验不会包含路径、URI 或空白的配置 alias。
     *
     * @param value 候选值；不可为空。
     * @param label 参数标签；不可为空。
     * @return 原值；不可为空。
     * @throws IllegalArgumentException 格式非法时抛出，不回显候选值。
     */
    public static String requireSafeAlias(final String value, final String label) {
        String checked = Objects.requireNonNull(value, label).trim();
        if (!SAFE_ALIAS.matcher(checked).matches()) {
            throw new IllegalArgumentException(label + " must be a safe alias");
        }
        return checked;
    }
}
