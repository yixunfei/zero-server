/*
 * ${generatedMarker}. Do not edit manually.
 */
using System;

namespace ${namespace};

/// <summary>
/// ${messageName} payload 编解码器。
/// </summary>
public static class ${codecName}
{
    /// <summary>写入 payload。</summary>
    public static void Write(ZeroWriter writer, ${messageName} message)
    {
        ArgumentNullException.ThrowIfNull(writer);
        ArgumentNullException.ThrowIfNull(message);
        int objectMarker = writer.BeginObject();
<#if hasNullableFields>
        writer.WritePresenceBits(${nullableFieldCount?c}, index => index switch
        {
<#list writeFields as field>
<#if field.nullable>
            ${field.presenceIndex?c} => ${field.presentExpression},
</#if>
</#list>
            _ => false
        });
</#if>
<#list writeFields as field>
<#if field.nullable>
        if (${field.presentExpression})
        {
${field.writeCode}        }
<#else>
${field.writeCode}</#if>
</#list>
        writer.EndObject(objectMarker);
    }

    /// <summary>读取 payload。</summary>
    public static ${messageName} Read(ZeroReader reader)
    {
        ArgumentNullException.ThrowIfNull(reader);
        int objectEnd = reader.BeginObject();
        ${messageName} message = new ${messageName}();
<#if hasNullableFields>
        bool[] presence = reader.HasRemainingInObject(objectEnd) ? reader.ReadPresenceBits() : Array.Empty<bool>();
</#if>
<#list readFields as field>
<#if field.nullable>
        if (IsPresent(presence, ${field.presenceIndex?c}) && reader.HasRemainingInObject(objectEnd))
        {
            message.${field.name} = ${field.readExpression};
        }
<#else>
        if (reader.HasRemainingInObject(objectEnd))
        {
            message.${field.name} = ${field.readExpression};
        }
</#if>
</#list>
        reader.EndObject(objectEnd);
        return message;
    }
<#if hasNullableFields>

    private static bool IsPresent(bool[] presence, int index)
    {
        return index >= 0 && index < presence.Length && presence[index];
    }
</#if>
}
