package group.zn.zero.codegen.generator.java;

import group.zn.zero.codegen.model.ProtocolField;
import group.zn.zero.codegen.model.ProtocolType;
import group.zn.zero.codegen.model.ProtocolTypeKind;
import java.util.Set;

/**
 * Java 生成类型映射器。
 *
 * @author zn
 */
public final class JavaTypeMapper {

    /**
     * 生成字段 Java 类型。
     *
     * @param field 协议字段；不可为空。
     * @param imports Java import 集合；可变、有序、不可为空、线程不安全。
     * @return Java 类型名称；不可为空。
     */
    public String fieldType(final ProtocolField field, final Set<String> imports) {
        return fieldType(field, imports, "");
    }

    /**
     * 生成字段 Java 类型。
     *
     * @param field 协议字段；不可为空。
     * @param imports Java import 集合；可变、有序、不可为空、线程不安全。
     * @param messageSuffix 协议消息类名后缀；可为空字符串。
     * @return Java 类型名称；不可为空。
     */
    public String fieldType(final ProtocolField field, final Set<String> imports, final String messageSuffix) {
        return javaType(field.type(), field.nullable(), false, imports, messageSuffix);
    }

    /**
     * 生成字段默认值。
     *
     * @param field 协议字段；不可为空。
     * @param imports Java import 集合；可变、有序、不可为空、线程不安全。
     * @return 默认值表达式；为空表示不需要显式初始化。
     */
    public String defaultValue(final ProtocolField field, final Set<String> imports) {
        return defaultValue(field, imports, "");
    }

    /**
     * 生成字段默认值。
     *
     * @param field 协议字段；不可为空。
     * @param imports Java import 集合；可变、有序、不可为空、线程不安全。
     * @param messageSuffix 协议消息类名后缀；可为空字符串。
     * @return 默认值表达式；为空表示不需要显式初始化。
     */
    public String defaultValue(final ProtocolField field, final Set<String> imports, final String messageSuffix) {
        if (field.nullable()) {
            return "null";
        }
        return switch (field.type().kind()) {
            case SCALAR -> scalarDefaultValue(field.type().name());
            case LIST -> {
                imports.add("java.util.ArrayList");
                yield "new ArrayList<>()";
            }
            case SET -> {
                imports.add("java.util.LinkedHashSet");
                yield "new LinkedHashSet<>()";
            }
            case MAP -> {
                imports.add("java.util.LinkedHashMap");
                yield "new LinkedHashMap<>()";
            }
            case ARRAY -> arrayDefaultValue(field.type(), imports, messageSuffix);
            default -> "";
        };
    }

    /**
     * 生成 Java 类型。
     *
     * @param type 协议类型；不可为空。
     * @param nullable 当前类型是否可空。
     * @param imports Java import 集合；可变、有序、不可为空、线程不安全。
     * @return Java 类型名称；不可为空。
     */
    public String javaType(final ProtocolType type, final boolean nullable, final Set<String> imports) {
        return javaType(type, nullable, imports, "");
    }

    /**
     * 生成 Java 类型。
     *
     * @param type 协议类型；不可为空。
     * @param nullable 当前类型是否可空。
     * @param imports Java import 集合；可变、有序、不可为空、线程不安全。
     * @param messageSuffix 协议消息类名后缀；可为空字符串。
     * @return Java 类型名称；不可为空。
     */
    public String javaType(
            final ProtocolType type,
            final boolean nullable,
            final Set<String> imports,
            final String messageSuffix) {
        return javaType(type, nullable, false, imports, messageSuffix);
    }

    private String javaType(
            final ProtocolType type,
            final boolean nullable,
            final boolean genericContext,
            final Set<String> imports,
            final String messageSuffix) {
        return switch (type.kind()) {
            case SCALAR -> scalarJavaType(type.name(), nullable || genericContext);
            case ENUM -> type.name();
            case MESSAGE -> type.name() + messageSuffix;
            case LIST -> {
                imports.add("java.util.List");
                yield "List<" + javaType(type.arguments().get(0), false, true, imports, messageSuffix) + ">";
            }
            case SET -> {
                imports.add("java.util.Set");
                yield "Set<" + javaType(type.arguments().get(0), false, true, imports, messageSuffix) + ">";
            }
            case MAP -> {
                imports.add("java.util.Map");
                yield "Map<"
                        + javaType(type.arguments().get(0), false, true, imports, messageSuffix)
                        + ", "
                        + javaType(type.arguments().get(1), false, true, imports, messageSuffix)
                        + ">";
            }
            case ARRAY -> javaType(type.arguments().get(0), false, false, imports, messageSuffix) + "[]";
            case OPTIONAL -> javaType(type.arguments().get(0), true, genericContext, imports, messageSuffix);
            case NULL -> "Object";
        };
    }

    private String scalarJavaType(final String name, final boolean boxed) {
        return switch (name) {
            case "boolean" -> boxed ? "Boolean" : "boolean";
            case "byte" -> boxed ? "Byte" : "byte";
            case "short" -> boxed ? "Short" : "short";
            case "int", "uint", "id", "count" -> boxed ? "Integer" : "int";
            case "long", "ulong" -> boxed ? "Long" : "long";
            case "float" -> boxed ? "Float" : "float";
            case "double" -> boxed ? "Double" : "double";
            case "string" -> "String";
            case "bytes" -> "byte[]";
            default -> "Object";
        };
    }

    private String scalarDefaultValue(final String name) {
        return switch (name) {
            case "string" -> "\"\"";
            case "bytes" -> "new byte[0]";
            default -> "";
        };
    }

    private String arrayDefaultValue(final ProtocolType type, final Set<String> imports, final String messageSuffix) {
        ArrayShape shape = arrayShape(type, imports, messageSuffix);
        if (shape.elementType().contains("<")) {
            return "";
        }
        StringBuilder builder = new StringBuilder("new ")
                .append(shape.elementType())
                .append("[0]");
        for (int index = 1; index < shape.dimensions(); index++) {
            builder.append("[]");
        }
        return builder.toString();
    }

    private ArrayShape arrayShape(final ProtocolType type, final Set<String> imports, final String messageSuffix) {
        int dimensions = 0;
        ProtocolType current = type;
        while (current.kind() == ProtocolTypeKind.ARRAY) {
            dimensions++;
            current = current.arguments().get(0);
        }
        return new ArrayShape(javaType(current, false, false, imports, messageSuffix), dimensions);
    }

    /**
     * 数组形状。
     *
     * @param elementType 数组元素 Java 类型。
     * @param dimensions 数组维度。
     */
    private record ArrayShape(String elementType, int dimensions) {
    }
}
