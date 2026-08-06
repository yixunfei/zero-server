/*
 * ${generatedMarker}. Do not edit manually.
 */
import { ZeroGeneratedPayload } from './zero-protocol-runtime';
<#list imports as import>
import { ${import.name} } from './${import.file}';
</#list>

/**
 * ${comment}
 */
export class ${name} implements ZeroGeneratedPayload {
<#list fields as field>
  /** ${field.comment} */
  ${field.name}: ${field.type} = ${field.defaultValue};

</#list>
}
