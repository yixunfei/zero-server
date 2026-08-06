/*
 * ${generatedMarker}. Do not edit manually.
 */
package ${packageName};

<#list imports as import>
import ${import};
</#list>

/**
 * ${messageName} payload 编解码器。
 */
public final class ${codecName} implements ZeroPayloadCodec<${messageName}> {

    /**
     * 单例实例。
     */
    public static final ${codecName} INSTANCE = new ${codecName}();

    /**
     * 创建 payload 编解码器。
     */
    private ${codecName}() {
    }

    /**
     * 返回提供者名称。
     *
     * @return 提供者名称；不可为空；线程安全。
     */
    @Override
    public String name() {
        return "${codecProviderName}";
    }

    /**
     * 返回消息类型。
     *
     * @return 消息类型；不可为空；线程安全。
     */
    @Override
    public Class<${messageName}> messageType() {
        return ${messageName}.class;
    }

    /**
     * 写入 payload。
     *
     * @param writer 写入器；不可为空。
     * @param message 消息；不可为空。
     * @throws NullPointerException 当写入器或消息为空时抛出。
     */
    @Override
    public void write(final ZeroWriter writer, final ${messageName} message) {
        Objects.requireNonNull(writer, "writer");
        Objects.requireNonNull(message, "message");
        int objectMarker = writer.beginObject();
<#if hasNullableFields>
        writer.writePresenceBits(${nullableFieldCount?c}, index -> switch (index) {
<#list writeFields as field>
<#if field.nullable>
            case ${field.presenceIndex?c} -> ${field.presentExpression};
</#if>
</#list>
            default -> false;
        });
</#if>
<#list writeFields as field>
<#if field.nullable>
        if (${field.presentExpression}) {
${field.writeCode}        }
<#else>
${field.writeCode}</#if>
</#list>
        writer.endObject(objectMarker);
    }

    /**
     * 读取 payload。
     *
     * @param reader 读取器；不可为空。
     * @return 消息；不可为空；线程不安全。
     * @throws NullPointerException 当读取器为空时抛出。
     */
    @Override
<#if uncheckedRead>
    @SuppressWarnings("unchecked")
</#if>
    public ${messageName} read(final ZeroReader reader) {
        Objects.requireNonNull(reader, "reader");
        int objectEnd = reader.beginObject();
        ${messageName} message = new ${messageName}();
<#if hasNullableFields>
        boolean[] presence = reader.hasRemainingInObject(objectEnd) ? reader.readPresenceBits() : new boolean[0];
</#if>
<#list readFields as field>
<#if field.nullable>
        if (isPresent(presence, ${field.presenceIndex?c}) && reader.hasRemainingInObject(objectEnd)) {
            message.${field.name} = ${field.readExpression};
        }
<#else>
        if (reader.hasRemainingInObject(objectEnd)) {
            message.${field.name} = ${field.readExpression};
        }
</#if>
</#list>
        reader.endObject(objectEnd);
        return message;
    }
<#if hasNullableFields>

    /**
     * 判断 nullable 字段是否存在。
     *
     * @param presence presence 位图；不可为空。
     * @param index 字段序号。
     * @return true 表示字段在线格式中存在。
     */
    private static boolean isPresent(final boolean[] presence, final int index) {
        return index >= 0 && index < presence.length && presence[index];
    }
</#if>
}
