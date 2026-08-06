package group.zn.zero.codegen.generator.client;

import group.zn.zero.codegen.model.ProtocolField;
import group.zn.zero.codegen.model.ProtocolMessage;
import group.zn.zero.codegen.model.ProtocolType;
import group.zn.zero.codegen.model.ProtocolTypeKind;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * 客户端生成通用辅助函数。
 *
 * @author zn
 */
public final class ClientCodegenSupport {

    /**
     * 禁止实例化。
     */
    private ClientCodegenSupport() {
    }

    /**
     * 生成 Java 风格的包路径。
     *
     * @param namespace 命名空间；不可为空。
     * @return 包路径；不可为空；线程安全。
     */
    public static Path packagePath(final String namespace) {
        String value = Objects.requireNonNull(namespace, "namespace").trim();
        if (value.isBlank()) {
            return Path.of("");
        }
        return Path.of(value.replace('.', '/'));
    }

    /**
     * 生成文件安全的常量名。
     *
     * @param value 原始名称；不可为空。
     * @return 常量名；不可为空；线程安全。
     */
    public static String constantName(final String value) {
        StringBuilder builder = new StringBuilder(value.length() + 8);
        char previous = 0;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isUpperCase(current) && index > 0 && Character.isLowerCase(previous)) {
                builder.append('_');
            }
            if (Character.isLetterOrDigit(current)) {
                builder.append(Character.toUpperCase(current));
            } else if (builder.length() > 0 && builder.charAt(builder.length() - 1) != '_') {
                builder.append('_');
            }
            previous = current;
        }
        return builder.toString();
    }

    /**
     * 生成 PascalCase 名称。
     *
     * @param value 原始名称；不可为空。
     * @return PascalCase 名称；不可为空；线程安全。
     */
    public static String pascalCase(final String value) {
        StringBuilder builder = new StringBuilder(value.length());
        boolean upperNext = true;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (!Character.isLetterOrDigit(current)) {
                upperNext = true;
                continue;
            }
            builder.append(upperNext ? Character.toUpperCase(current) : current);
            upperNext = false;
        }
        return builder.length() == 0 ? "Value" : builder.toString();
    }

    /**
     * 生成 camelCase 名称。
     *
     * @param value 原始名称；不可为空。
     * @return camelCase 名称；不可为空；线程安全。
     */
    public static String camelCase(final String value) {
        String pascal = pascalCase(value);
        if (pascal.isEmpty()) {
            return "value";
        }
        return Character.toLowerCase(pascal.charAt(0)) + pascal.substring(1);
    }

    /**
     * 生成 C# 命名空间。
     *
     * @param value 原始命名空间；不可为空。
     * @return 规范化命名空间；不可为空；线程安全。
     */
    public static String toCSharpNamespace(final String value) {
        String[] parts = Objects.requireNonNull(value, "value").split("\\.");
        List<String> normalized = new ArrayList<>();
        for (String part : parts) {
            if (!part.isBlank()) {
                normalized.add(pascalCase(part));
            }
        }
        return normalized.isEmpty() ? "Generated" : String.join(".", normalized);
    }

    /**
     * 生成 TypeScript 模块名。
     *
     * @param value 原始命名空间；不可为空。
     * @return 模块标识；不可为空；线程安全。
     */
    public static String toTypeScriptNamespace(final String value) {
        StringBuilder builder = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isLetterOrDigit(current) || current == '_' || current == '.') {
                builder.append(current);
            } else if (builder.length() > 0 && builder.charAt(builder.length() - 1) != '_') {
                builder.append('_');
            }
        }
        return builder.length() == 0 ? "generated" : builder.toString();
    }

    /**
     * 将字段类型转成用于客户端的可读名称。
     *
     * @param field 协议字段；不可为空。
     * @return 类型名称；不可为空；线程安全。
     */
    public static String fieldTypeName(final ProtocolField field) {
        return typeName(field.type(), field.nullable(), false);
    }

    /**
     * 转换类型名称。
     *
     * @param type 协议类型；不可为空。
     * @param nullable 是否可空。
     * @param inGeneric 是否在泛型上下文。
     * @return 类型名称；不可为空；线程安全。
     */
    public static String typeName(final ProtocolType type, final boolean nullable, final boolean inGeneric) {
        return switch (type.kind()) {
            case SCALAR -> scalarTypeName(type.name(), nullable);
            case ENUM, MESSAGE -> type.name() + (nullable ? "?" : "");
            case LIST -> "List<" + typeName(type.arguments().get(0), false, true) + ">" + (nullable ? "?" : "");
            case SET -> "Set<" + typeName(type.arguments().get(0), false, true) + ">" + (nullable ? "?" : "");
            case MAP -> "Map<"
                    + typeName(type.arguments().get(0), false, true)
                    + ", "
                    + typeName(type.arguments().get(1), false, true)
                    + ">" + (nullable ? "?" : "");
            case ARRAY -> typeName(type.arguments().get(0), false, false) + "[]" + (nullable ? "?" : "");
            case OPTIONAL -> typeName(type.arguments().get(0), true, inGeneric);
            case NULL -> "Object";
        };
    }

    /**
     * 生成默认值表达式。
     *
     * @param field 协议字段；不可为空。
     * @return 默认值表达式；可能为空。
     */
    public static String defaultValue(final ProtocolField field) {
        if (field.nullable()) {
            return "null";
        }
        return switch (field.type().kind()) {
            case SCALAR -> scalarDefaultValue(field.type().name());
            case LIST -> "new ArrayList<>()";
            case SET -> "new LinkedHashSet<>()";
            case MAP -> "new LinkedHashMap<>()";
            case ARRAY -> arrayDefaultValue(field.type());
            default -> "";
        };
    }

    /**
     * 收集类型依赖。
     *
     * @param type 协议类型；不可为空。
     * @param imports import 目标集合；不可为空。
     */
    public static void collectImports(final ProtocolType type, final Set<String> imports) {
        switch (type.kind()) {
            case LIST -> {
                imports.add("java.util.List");
                collectImports(type.arguments().get(0), imports);
            }
            case SET -> {
                imports.add("java.util.Set");
                collectImports(type.arguments().get(0), imports);
            }
            case MAP -> {
                imports.add("java.util.Map");
                collectImports(type.arguments().get(0), imports);
                collectImports(type.arguments().get(1), imports);
            }
            case ARRAY, OPTIONAL -> collectImports(type.arguments().get(0), imports);
            case ENUM, MESSAGE, SCALAR, NULL -> {
                // 客户端 runtime 自带基本类型支持。
            }
        }
    }

    /**
     * 收集消息依赖。
     *
     * @param message 协议消息；不可为空。
     * @param imports import 目标集合；不可为空。
     */
    public static void collectMessageImports(final ProtocolMessage message, final Set<String> imports) {
        for (ProtocolField field : message.fields()) {
            collectImports(field.type(), imports);
        }
    }

    /**
     * 生成值是否需要可空 presence 标记。
     *
     * @param type 协议类型；不可为空。
     * @return true 表示需要 presence；线程安全。
     */
    public static boolean isNullablePayload(final ProtocolType type) {
        return type.kind() == ProtocolTypeKind.OPTIONAL;
    }

    /**
     * 生成客户端类型名。
     *
     * @param scalarName 标量名称。
     * @param boxed 是否装箱。
     * @return 类型名称；不可为空；线程安全。
     */
    public static String scalarTypeName(final String scalarName, final boolean nullable) {
        return switch (scalarName) {
            case "boolean" -> nullable ? "bool?" : "bool";
            case "byte" -> nullable ? "byte?" : "byte";
            case "short" -> nullable ? "short?" : "short";
            case "int", "uint", "id", "count" -> nullable ? "int?" : "int";
            case "long" -> nullable ? "long?" : "long";
            case "ulong" -> nullable ? "ulong?" : "ulong";
            case "float" -> nullable ? "float?" : "float";
            case "double" -> nullable ? "double?" : "double";
            case "string" -> nullable ? "string?" : "string";
            case "bytes" -> nullable ? "byte[]?" : "byte[]";
            default -> "object";
        };
    }

    /**
     * 生成标量默认值。
     *
     * @param scalarName 标量名称。
     * @return 默认值表达式；可能为空。
     */
    public static String scalarDefaultValue(final String scalarName) {
        return switch (scalarName) {
            case "string" -> "\"\"";
            case "bytes" -> "new byte[0]";
            case "ulong" -> "0UL";
            default -> "";
        };
    }

    /**
     * 生成数组默认值。
     *
     * @param type 协议类型；不可为空。
     * @return 默认值表达式；可能为空。
     */
    public static String arrayDefaultValue(final ProtocolType type) {
        ProtocolType current = type;
        int dimensions = 0;
        while (current.kind() == ProtocolTypeKind.ARRAY) {
            dimensions++;
            current = current.arguments().get(0);
        }
        String elementType = typeName(current, false, false);
        if (elementType.contains("<")) {
            return "";
        }
        StringBuilder builder = new StringBuilder("new ").append(elementType).append("[0]");
        for (int index = 1; index < dimensions; index++) {
            builder.append("[]");
        }
        return builder.toString();
    }

    /**
     * 提取消息字段集。
     *
     * @param message 协议消息；不可为空。
     * @return 字段列表；不可为空；有序；线程安全。
     */
    public static List<ProtocolField> fieldsOf(final ProtocolMessage message) {
        return List.copyOf(message.fields());
    }

    /**
     * 生成消息字段 import 集合。
     *
     * @param message 协议消息；不可为空。
     * @return import 集合；不可为空；有序；线程安全。
     */
    public static Set<String> importsOf(final ProtocolMessage message) {
        Set<String> imports = new TreeSet<>();
        collectMessageImports(message, imports);
        return imports;
    }
}
