package group.zn.zero.codegen;

/**
 * CLI 与 GUI 共享的目标语言命名空间默认规则。
 *
 * @author zn
 */
final class CodegenNamespaceDefaults {

    private CodegenNamespaceDefaults() {
    }

    /** 将 Java 包名转换为 C# 惯用的 PascalCase 命名空间。 */
    static String csharp(final String value) {
        StringBuilder builder = new StringBuilder(value.length());
        boolean upperNext = true;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (!Character.isLetterOrDigit(current)) {
                upperNext = true;
                if (builder.length() > 0 && builder.charAt(builder.length() - 1) != '.') {
                    builder.append('.');
                }
                continue;
            }
            builder.append(upperNext ? Character.toUpperCase(current) : current);
            upperNext = false;
        }
        return builder.toString();
    }
}
