/*
 * ${generatedMarker}. Do not edit manually.
 */
import { ZeroReader, ZeroWriter } from './zero-protocol-runtime';
<#list imports as import>
import { ${import.name} } from './${import.file}';
</#list>

/**
 * ${messageName} payload 编解码器。
 */
export class ${codecName} {
  static write(writer: ZeroWriter, message: ${messageName}): void {
    const objectMarker = writer.beginObject();
<#if hasNullableFields>
    writer.writePresenceBits(${nullableFieldCount?c}, index => {
      switch (index) {
<#list writeFields as field>
<#if field.nullable>
        case ${field.presenceIndex?c}: return ${field.presentExpression};
</#if>
</#list>
        default: return false;
      }
    });
</#if>
<#list writeFields as field>
<#if field.nullable>
    if (${field.presentExpression}) {
${field.writeCode}    }
<#else>
${field.writeCode}</#if>
</#list>
    writer.endObject(objectMarker);
  }

  static read(reader: ZeroReader): ${messageName} {
    const objectEnd = reader.beginObject();
    const message = new ${messageName}();
<#if hasNullableFields>
    const presence = reader.hasRemainingInObject(objectEnd) ? reader.readPresenceBits() : [];
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
}
<#if hasNullableFields>

function isPresent(presence: boolean[], index: number): boolean {
  return index >= 0 && index < presence.length && presence[index];
}
</#if>
