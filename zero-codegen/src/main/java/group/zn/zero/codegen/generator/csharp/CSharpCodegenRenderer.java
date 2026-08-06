package group.zn.zero.codegen.generator.csharp;

import group.zn.zero.codegen.generator.client.AbstractTemplateCodegenRenderer;
import group.zn.zero.codegen.generator.client.ClientCodegenSupport;
import group.zn.zero.codegen.model.CodegenLanguage;
import group.zn.zero.codegen.model.CodegenRequest;
import group.zn.zero.codegen.model.ProtocolEnum;
import group.zn.zero.codegen.model.ProtocolEnumValue;
import group.zn.zero.codegen.model.ProtocolField;
import group.zn.zero.codegen.model.ProtocolMessage;
import group.zn.zero.codegen.model.ProtocolType;
import group.zn.zero.codegen.model.ProtocolTypeKind;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * C# 客户端代码生成渲染器。
 *
 * @author zn
 */
public final class CSharpCodegenRenderer extends AbstractTemplateCodegenRenderer {

    /**
     * 创建 C# 客户端代码生成渲染器。
     */
    public CSharpCodegenRenderer() {
        super("/codegen/csharp");
    }

    /**
     * 渲染 C# 生成物。
     *
     * @param request 代码生成请求；不可为空。
     */
    public void render(final CodegenRequest request) {
        Objects.requireNonNull(request, "request");
        Path root = request.outputDir(CodegenLanguage.CSHARP);
        String namespace = ClientCodegenSupport.toCSharpNamespace(request.namespace(CodegenLanguage.CSHARP));
        String dtoSuffix = request.dtoSuffix(CodegenLanguage.CSHARP);

        writeGeneratedFile(root.resolve("ZeroProtocolRuntime.cs"),
                renderTemplate("runtime.cs.ftl", runtimeModel(namespace)));
        writeGeneratedFile(root.resolve("ProtocolIds.cs"),
                renderTemplate("protocolIds.cs.ftl", protocolIdsModel(namespace, request.document().protocols())));

        for (ProtocolEnum item : request.document().enums()) {
            writeGeneratedFile(root.resolve(item.name() + ".cs"),
                    renderTemplate("enum.cs.ftl", enumModel(namespace, item)));
        }
        for (ProtocolMessage message : request.document().messages()) {
            String dtoName = dtoName(message.name(), dtoSuffix);
            writeGeneratedFile(root.resolve(dtoName + ".cs"),
                    renderTemplate("message.cs.ftl", messageModel(namespace, message, dtoSuffix)));
            writeGeneratedFile(root.resolve(dtoName + "Codec.cs"),
                    renderTemplate("codec.cs.ftl", codecModel(namespace, message, dtoSuffix)));
        }
    }

    private Map<String, Object> runtimeModel(final String namespace) {
        Map<String, Object> model = baseModel(namespace);
        model.put("namespace", namespace);
        return model;
    }

    private Map<String, Object> enumModel(final String namespace, final ProtocolEnum item) {
        Map<String, Object> model = baseModel(namespace);
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
        return model;
    }

    private Map<String, Object> messageModel(
            final String namespace,
            final ProtocolMessage message,
            final String dtoSuffix) {
        Map<String, Object> model = baseModel(namespace);
        model.put("name", dtoName(message.name(), dtoSuffix));
        model.put("comment", commentOrDefault(message.comment(), message.name() + " 协议消息。"));
        List<Map<String, Object>> fields = new ArrayList<>();
        for (ProtocolField field : message.fields()) {
            Map<String, Object> fieldModel = new LinkedHashMap<>();
            fieldModel.put("name", field.name());
            fieldModel.put("type", csharpType(field.type(), field.nullable(), false, dtoSuffix));
            fieldModel.put("defaultValue", csharpDefaultValue(field, dtoSuffix));
            fieldModel.put("comment", commentOrDefault(field.comment(), field.name()));
            fields.add(fieldModel);
        }
        model.put("fields", fields);
        return model;
    }

    private Map<String, Object> protocolIdsModel(final String namespace, final List<group.zn.zero.protocol.ProtocolDefinition> protocols) {
        Map<String, Object> model = baseModel(namespace);
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
        model.put("items", items);
        model.put("maxId", maxId);
        return model;
    }

    private Map<String, Object> codecModel(
            final String namespace,
            final ProtocolMessage message,
            final String dtoSuffix) {
        Map<String, Object> model = baseModel(namespace);
        model.put("messageName", dtoName(message.name(), dtoSuffix));
        model.put("codecName", dtoName(message.name(), dtoSuffix) + "Codec");

        List<Map<String, Object>> writeFields = new ArrayList<>();
        List<Map<String, Object>> readFields = new ArrayList<>();
        int nullableIndex = 0;
        for (ProtocolField field : message.fields()) {
            Map<String, Object> writeField = new LinkedHashMap<>();
            writeField.put("name", field.name());
            writeField.put("nullable", field.nullable());
            writeField.put("presenceIndex", field.nullable() ? nullableIndex : -1);
            writeField.put("presentExpression", "message." + field.name() + " != null");
            String writeIndent = field.nullable() ? "            " : "        ";
            writeField.put("writeCode", writeValue(
                    field.type(),
                    "writer",
                    csharpWriteAccess(field.type(), field.nullable(), "message." + field.name()),
                    writeIndent,
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
        model.put("hasNullableFields", nullableIndex > 0);
        model.put("nullableFieldCount", nullableIndex);
        model.put("writeFields", writeFields);
        model.put("readFields", readFields);
        return model;
    }

    private String writeValue(
            final ProtocolType type,
            final String writer,
            final String value,
            final String indent,
            final String dtoSuffix) {
        StringBuilder builder = new StringBuilder();
        switch (type.kind()) {
            case SCALAR -> appendLine(builder, indent, writer + "." + scalarWriteMethod(type.name()) + "(" + value + ");");
            case ENUM -> appendLine(builder, indent, writer + ".WriteInt((int) " + value + ");");
            case MESSAGE -> appendLine(builder, indent, dtoName(type.name(), dtoSuffix) + "Codec.Write(" + writer
                    + ", " + value + ");");
            case LIST, SET -> {
                String itemWriter = childName(writer, "ItemWriter");
                String itemValue = childName(value, "ItemValue");
                appendLine(builder, indent, writer + ".WriteCollection(" + value + ", (" + itemWriter + ", "
                        + itemValue + ") => {");
                builder.append(writeValue(type.arguments().get(0), itemWriter, itemValue, indent + "    ",
                        dtoSuffix));
                appendLine(builder, indent, "});");
            }
            case MAP -> {
                String keyWriter = childName(writer, "KeyWriter");
                String keyValue = childName(value, "KeyValue");
                String valueWriter = childName(writer, "ValueWriter");
                String mapValue = childName(value, "MapValue");
                appendLine(builder, indent, writer + ".WriteMap(" + value + ", (" + keyWriter + ", " + keyValue
                        + ") => {");
                builder.append(writeValue(type.arguments().get(0), keyWriter, keyValue, indent + "    ",
                        dtoSuffix));
                appendLine(builder, indent, "}, (" + valueWriter + ", " + mapValue + ") => {");
                builder.append(writeValue(type.arguments().get(1), valueWriter, mapValue, indent + "    ",
                        dtoSuffix));
                appendLine(builder, indent, "});");
            }
            case ARRAY -> {
                String itemWriter = childName(writer, "ArrayWriter");
                String itemValue = childName(value, "ArrayValue");
                appendLine(builder, indent, writer + ".WriteArray(" + value + ", (" + itemWriter + ", "
                        + itemValue + ") => {");
                builder.append(writeValue(type.arguments().get(0), itemWriter, itemValue, indent + "    ",
                        dtoSuffix));
                appendLine(builder, indent, "});");
            }
            case OPTIONAL -> {
                appendLine(builder, indent, writer + ".WriteBoolean(" + value + " != null);");
                appendLine(builder, indent, "if (" + value + " != null) {");
                builder.append(writeValue(
                        type.arguments().get(0),
                        writer,
                        csharpWriteAccess(type.arguments().get(0), true, value),
                        indent + "    ",
                        dtoSuffix));
                appendLine(builder, indent, "}");
            }
            case NULL -> appendLine(builder, indent, "throw new ArgumentException(\"null type is reserved\");");
        }
        return builder.toString();
    }

    private String readValue(final ProtocolType type, final String reader, final String dtoSuffix) {
        return switch (type.kind()) {
            case SCALAR -> reader + "." + scalarReadMethod(type.name()) + "()";
            case ENUM -> "(" + type.name() + ") " + reader + ".ReadInt()";
            case MESSAGE -> dtoName(type.name(), dtoSuffix) + "Codec.Read(" + reader + ")";
            case LIST -> reader + ".ReadList(" + childName(reader, "ItemReader") + " => "
                    + readValue(type.arguments().get(0), childName(reader, "ItemReader"), dtoSuffix) + ")";
            case SET -> reader + ".ReadSet(" + childName(reader, "ItemReader") + " => "
                    + readValue(type.arguments().get(0), childName(reader, "ItemReader"), dtoSuffix) + ")";
            case MAP -> reader + ".ReadMap(" + childName(reader, "KeyReader") + " => "
                    + readValue(type.arguments().get(0), childName(reader, "KeyReader"), dtoSuffix) + ", "
                    + childName(reader, "ValueReader") + " => "
                    + readValue(type.arguments().get(1), childName(reader, "ValueReader"), dtoSuffix) + ")";
            case ARRAY -> reader + ".ReadArray(" + childName(reader, "ArrayReader") + " => "
                    + readValue(type.arguments().get(0), childName(reader, "ArrayReader"), dtoSuffix) + ")";
            case OPTIONAL -> "(" + reader + ".ReadBoolean() ? "
                    + readValue(type.arguments().get(0), reader, dtoSuffix) + " : null)";
            case NULL -> "null";
        };
    }

    private String csharpType(
            final ProtocolType type,
            final boolean nullable,
            final boolean genericContext,
            final String dtoSuffix) {
        return switch (type.kind()) {
            case SCALAR -> scalarCSharpType(type.name(), nullable);
            case ENUM -> type.name() + (nullable ? "?" : "");
            case MESSAGE -> dtoName(type.name(), dtoSuffix) + (nullable ? "?" : "");
            case LIST -> "List<" + csharpType(type.arguments().get(0), false, true, dtoSuffix) + ">"
                    + (nullable ? "?" : "");
            case SET -> "HashSet<" + csharpType(type.arguments().get(0), false, true, dtoSuffix) + ">"
                    + (nullable ? "?" : "");
            case MAP -> "Dictionary<"
                    + csharpType(type.arguments().get(0), false, true, dtoSuffix)
                    + ", "
                    + csharpType(type.arguments().get(1), false, true, dtoSuffix)
                    + ">" + (nullable ? "?" : "");
            case ARRAY -> csharpType(type.arguments().get(0), false, false, dtoSuffix) + "[]"
                    + (nullable ? "?" : "");
            case OPTIONAL -> csharpType(type.arguments().get(0), true, genericContext, dtoSuffix);
            case NULL -> "object";
        };
    }

    private String csharpDefaultValue(final ProtocolField field, final String dtoSuffix) {
        if (field.nullable()) {
            return "null";
        }
        return switch (field.type().kind()) {
            case SCALAR -> scalarDefaultValue(field.type().name());
            case ENUM -> "default";
            case MESSAGE -> "new " + dtoName(field.type().name(), dtoSuffix) + "()";
            case LIST -> "new List<" + csharpType(field.type().arguments().get(0), false, true, dtoSuffix) + ">()";
            case SET -> "new HashSet<" + csharpType(field.type().arguments().get(0), false, true, dtoSuffix)
                    + ">()";
            case MAP -> "new Dictionary<"
                    + csharpType(field.type().arguments().get(0), false, true, dtoSuffix)
                    + ", "
                    + csharpType(field.type().arguments().get(1), false, true, dtoSuffix)
                    + ">()";
            case ARRAY -> "Array.Empty<" + csharpType(field.type().arguments().get(0), false, false, dtoSuffix)
                    + ">()";
            case OPTIONAL -> "null";
            case NULL -> "null";
        };
    }

    private String scalarCSharpType(final String scalarName, final boolean nullable) {
        return switch (scalarName) {
            case "boolean" -> nullable ? "bool?" : "bool";
            case "byte" -> nullable ? "byte?" : "byte";
            case "short" -> nullable ? "short?" : "short";
            case "int" -> nullable ? "int?" : "int";
            case "uint", "id", "count" -> nullable ? "int?" : "int";
            case "long" -> nullable ? "long?" : "long";
            case "ulong" -> nullable ? "ulong?" : "ulong";
            case "float" -> nullable ? "float?" : "float";
            case "double" -> nullable ? "double?" : "double";
            case "string" -> nullable ? "string?" : "string";
            case "bytes" -> nullable ? "byte[]?" : "byte[]";
            default -> "object";
        };
    }

    private String scalarDefaultValue(final String scalarName) {
        return switch (scalarName) {
            case "string" -> "string.Empty";
            case "bytes" -> "Array.Empty<byte>()";
            case "ulong" -> "0UL";
            default -> "default";
        };
    }

    private String scalarWriteMethod(final String scalarName) {
        return switch (scalarName) {
            case "boolean" -> "WriteBoolean";
            case "byte" -> "WriteByte";
            case "short" -> "WriteShort";
            case "int" -> "WriteInt";
            case "uint", "id", "count" -> "WriteUnsignedInt";
            case "long" -> "WriteLong";
            case "ulong" -> "WriteUnsignedLong";
            case "float" -> "WriteFloat";
            case "double" -> "WriteDouble";
            case "string" -> "WriteString";
            case "bytes" -> "WriteByteArray";
            default -> throw new IllegalStateException("unsupported scalar type: " + scalarName);
        };
    }

    private String scalarReadMethod(final String scalarName) {
        return switch (scalarName) {
            case "boolean" -> "ReadBoolean";
            case "byte" -> "ReadByte";
            case "short" -> "ReadShort";
            case "int" -> "ReadInt";
            case "uint", "id", "count" -> "ReadUnsignedInt";
            case "long" -> "ReadLong";
            case "ulong" -> "ReadUnsignedLong";
            case "float" -> "ReadFloat";
            case "double" -> "ReadDouble";
            case "string" -> "ReadString";
            case "bytes" -> "ReadByteArray";
            default -> throw new IllegalStateException("unsupported scalar type: " + scalarName);
        };
    }

    private String csharpWriteAccess(final ProtocolType type, final boolean nullable, final String value) {
        if (!nullable) {
            return value;
        }
        return switch (type.kind()) {
            case SCALAR -> isNullableReferenceScalar(type.name()) ? value : value + ".Value";
            case ENUM -> value + ".Value";
            case MESSAGE, LIST, SET, MAP, ARRAY, OPTIONAL, NULL -> value;
        };
    }

    private boolean isNullableReferenceScalar(final String scalarName) {
        return "string".equals(scalarName) || "bytes".equals(scalarName);
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
        model.put("imports", List.of());
        return model;
    }
}
