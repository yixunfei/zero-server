package group.zn.zero.codegen.generator.gdscript;

import group.zn.zero.codegen.generator.client.AbstractTemplateCodegenRenderer;
import group.zn.zero.codegen.generator.client.ClientCodegenSupport;
import group.zn.zero.codegen.model.CodegenLanguage;
import group.zn.zero.codegen.model.CodegenRequest;
import group.zn.zero.codegen.model.ProtocolEnum;
import group.zn.zero.codegen.model.ProtocolEnumValue;
import group.zn.zero.codegen.model.ProtocolField;
import group.zn.zero.codegen.model.ProtocolMessage;
import group.zn.zero.codegen.model.ProtocolType;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * GDScript 客户端代码生成渲染器。
 *
 * @author zn
 */
public final class GdScriptCodegenRenderer extends AbstractTemplateCodegenRenderer {

    /**
     * 创建 GDScript 客户端代码生成渲染器。
     */
    public GdScriptCodegenRenderer() {
        super("/codegen/gdscript");
    }

    /**
     * 渲染 GDScript 生成物。
     *
     * @param request 代码生成请求；不可为空。
     */
    public void render(final CodegenRequest request) {
        Objects.requireNonNull(request, "request");
        Path root = request.outputDir(CodegenLanguage.GDSCRIPT);
        String namespace = request.namespace(CodegenLanguage.GDSCRIPT);
        String dtoSuffix = request.dtoSuffix(CodegenLanguage.GDSCRIPT);
        Map<String, Object> model = baseModel(namespace);
        model.put("enums", enumModels(request.document().enums()));
        model.put("messages", messageModels(request.document().messages(), dtoSuffix));
        model.put("protocolIds", protocolIds(request.document().protocols()));
        writeGeneratedFile(root.resolve("zero_protocol.gd"), renderTemplate("zero_protocol.gd.ftl", model));
    }

    private List<Map<String, Object>> enumModels(final List<ProtocolEnum> enums) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (ProtocolEnum item : enums) {
            Map<String, Object> model = new LinkedHashMap<>();
            model.put("name", item.name());
            model.put("comment", commentOrDefault(item.comment(), item.name() + " 协议枚举。"));
            List<Map<String, Object>> values = new ArrayList<>();
            for (ProtocolEnumValue value : item.values()) {
                Map<String, Object> valueModel = new LinkedHashMap<>();
                valueModel.put("name", value.name());
                valueModel.put("value", value.value());
                valueModel.put("comment", commentOrDefault(value.comment(), value.name()));
                values.add(valueModel);
            }
            model.put("values", values);
            result.add(model);
        }
        return result;
    }

    private List<Map<String, Object>> messageModels(final List<ProtocolMessage> messages, final String dtoSuffix) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (ProtocolMessage message : messages) {
            Map<String, Object> model = new LinkedHashMap<>();
            model.put("name", dtoName(message.name(), dtoSuffix));
            model.put("comment", commentOrDefault(message.comment(), message.name() + " 协议消息。"));
            List<Map<String, Object>> fields = new ArrayList<>();
            List<Map<String, Object>> writeFields = new ArrayList<>();
            List<Map<String, Object>> readFields = new ArrayList<>();
            int nullableIndex = 0;
            for (ProtocolField field : message.fields()) {
                Map<String, Object> fieldModel = new LinkedHashMap<>();
                fieldModel.put("name", field.name());
                fieldModel.put("defaultValue", gdDefaultValue(field, dtoSuffix));
                fieldModel.put("comment", commentOrDefault(field.comment(), field.name()));
                fields.add(fieldModel);

                Map<String, Object> writeField = new LinkedHashMap<>();
                writeField.put("name", field.name());
                writeField.put("nullable", field.nullable());
                writeField.put("presenceIndex", field.nullable() ? nullableIndex : -1);
                writeField.put("presentExpression", "message." + field.name() + " != null");
                String indent = field.nullable() ? "            " : "        ";
                writeField.put("writeCode", writeValue(field.type(), "writer", "message." + field.name(), indent,
                        dtoSuffix));
                writeFields.add(writeField);

                Map<String, Object> readField = new LinkedHashMap<>();
                readField.put("name", field.name());
                readField.put("nullable", field.nullable());
                readField.put("presenceIndex", field.nullable() ? nullableIndex : -1);
                readField.put("readExpression", readValue(field.type(), "reader", dtoSuffix));
                readFields.add(readField);
                if (field.nullable()) {
                    nullableIndex++;
                }
            }
            model.put("fields", fields);
            model.put("writeFields", writeFields);
            model.put("readFields", readFields);
            model.put("hasNullableFields", nullableIndex > 0);
            model.put("nullableFieldCount", nullableIndex);
            result.add(model);
        }
        return result;
    }

    private List<Map<String, Object>> protocolIds(final List<group.zn.zero.protocol.ProtocolDefinition> protocols) {
        List<Map<String, Object>> items = new ArrayList<>();
        int maxId = 0;
        for (group.zn.zero.protocol.ProtocolDefinition protocol : protocols) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("constantName", ClientCodegenSupport.constantName(protocol.name()));
            item.put("id", protocol.id());
            item.put("comment", protocol.name() + " 协议号。");
            items.add(item);
            maxId = Math.max(maxId, protocol.id());
        }
        Map<String, Object> max = new LinkedHashMap<>();
        max.put("constantName", "MAX_ID");
        max.put("id", maxId);
        max.put("comment", "最大协议号。");
        items.add(0, max);
        return items;
    }

    private String writeValue(
            final ProtocolType type,
            final String writer,
            final String value,
            final String indent,
            final String dtoSuffix) {
        StringBuilder builder = new StringBuilder();
        switch (type.kind()) {
            case SCALAR -> appendLine(builder, indent, writer + "." + scalarWriteMethod(type.name()) + "(" + value + ")");
            case ENUM -> appendLine(builder, indent, writer + ".write_int(" + value + ")");
            case MESSAGE -> appendLine(builder, indent, dtoName(type.name(), dtoSuffix) + "Codec.write(" + writer
                    + ", " + value + ")");
            case LIST, SET, ARRAY -> {
                String itemWriter = childName(writer, "ItemWriter");
                String itemValue = childName(value, "ItemValue");
                appendLine(builder, indent, writer + ".write_array(" + value + ", func(" + itemWriter + ", "
                        + itemValue + "):");
                builder.append(writeValue(type.arguments().get(0), itemWriter, itemValue, indent + "    ",
                        dtoSuffix));
                appendLine(builder, indent, ")");
            }
            case MAP -> {
                String keyValue = childName(value, "Key");
                appendLine(builder, indent, writer + ".write_unsigned_int(" + value + ".size())");
                appendLine(builder, indent, "for " + keyValue + " in " + value + ".keys():");
                builder.append(writeValue(type.arguments().get(0), writer, keyValue, indent + "    ", dtoSuffix));
                builder.append(writeValue(type.arguments().get(1), writer, value + "[" + keyValue + "]",
                        indent + "    ", dtoSuffix));
            }
            case OPTIONAL -> {
                appendLine(builder, indent, writer + ".write_boolean(" + value + " != null)");
                appendLine(builder, indent, "if " + value + " != null:");
                builder.append(writeValue(type.arguments().get(0), writer, value, indent + "    ", dtoSuffix));
            }
            case NULL -> appendLine(builder, indent, "push_error(\"null type is reserved\")");
        }
        return builder.toString();
    }

    private String readValue(final ProtocolType type, final String reader, final String dtoSuffix) {
        return switch (type.kind()) {
            case SCALAR -> reader + "." + scalarReadMethod(type.name()) + "()";
            case ENUM -> reader + ".read_int()";
            case MESSAGE -> dtoName(type.name(), dtoSuffix) + "Codec.read(" + reader + ")";
            case LIST, SET, ARRAY -> reader + ".read_array(func(" + childName(reader, "ArrayReader") + "): return "
                    + readValue(type.arguments().get(0), childName(reader, "ArrayReader"), dtoSuffix) + ")";
            case MAP -> reader + ".read_map(func(" + childName(reader, "KeyReader") + "): return "
                    + readValue(type.arguments().get(0), childName(reader, "KeyReader"), dtoSuffix) + ", func("
                    + childName(reader, "ValueReader") + "): return "
                    + readValue(type.arguments().get(1), childName(reader, "ValueReader"), dtoSuffix) + ")";
            case OPTIONAL -> "(" + readValue(type.arguments().get(0), reader, dtoSuffix) + " if " + reader
                    + ".read_boolean() else null)";
            case NULL -> "null";
        };
    }

    private String gdDefaultValue(final ProtocolField field, final String dtoSuffix) {
        if (field.nullable()) {
            return "null";
        }
        return switch (field.type().kind()) {
            case SCALAR -> scalarDefaultValue(field.type().name());
            case ENUM -> "0";
            case MESSAGE -> dtoName(field.type().name(), dtoSuffix) + ".new()";
            case LIST, SET, ARRAY -> "[]";
            case MAP -> "{}";
            case OPTIONAL -> "null";
            case NULL -> "null";
        };
    }

    private String scalarDefaultValue(final String scalarName) {
        return switch (scalarName) {
            case "boolean" -> "false";
            case "string" -> "\"\"";
            case "bytes" -> "PackedByteArray()";
            default -> "0";
        };
    }

    private String scalarWriteMethod(final String scalarName) {
        return switch (scalarName) {
            case "boolean" -> "write_boolean";
            case "byte" -> "write_byte";
            case "short" -> "write_short";
            case "int" -> "write_int";
            case "uint", "id", "count" -> "write_unsigned_int";
            case "long" -> "write_long";
            case "ulong" -> "write_unsigned_long";
            case "float" -> "write_float";
            case "double" -> "write_double";
            case "string" -> "write_string";
            case "bytes" -> "write_byte_array";
            default -> throw new IllegalStateException("unsupported scalar type: " + scalarName);
        };
    }

    private String scalarReadMethod(final String scalarName) {
        return switch (scalarName) {
            case "boolean" -> "read_boolean";
            case "byte" -> "read_byte";
            case "short" -> "read_short";
            case "int" -> "read_int";
            case "uint", "id", "count" -> "read_unsigned_int";
            case "long" -> "read_long";
            case "ulong" -> "read_unsigned_long";
            case "float" -> "read_float";
            case "double" -> "read_double";
            case "string" -> "read_string";
            case "bytes" -> "read_byte_array";
            default -> throw new IllegalStateException("unsupported scalar type: " + scalarName);
        };
    }

    private String childName(final String owner, final String suffix) {
        StringBuilder builder = new StringBuilder(owner.length() + suffix.length());
        for (int index = 0; index < owner.length(); index++) {
            char value = owner.charAt(index);
            if (Character.isLetterOrDigit(value)) {
                builder.append(value);
            }
        }
        if (builder.length() == 0) {
            builder.append("value");
        }
        builder.append(suffix);
        return builder.toString();
    }

    private String dtoName(final String logicalName, final String dtoSuffix) {
        return logicalName + dtoSuffix;
    }

    private void appendLine(final StringBuilder builder, final String indent, final String line) {
        builder.append(indent).append(line).append(System.lineSeparator());
    }

    private String commentOrDefault(final String comment, final String defaultValue) {
        if (comment == null || comment.isBlank()) {
            return defaultValue;
        }
        return comment;
    }

    private Map<String, Object> baseModel(final String namespace) {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("generatedMarker", "Generated by zeroServer codegen");
        model.put("namespace", namespace);
        return model;
    }
}
