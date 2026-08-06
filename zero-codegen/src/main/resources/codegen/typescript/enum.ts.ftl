/*
 * ${generatedMarker}. Do not edit manually.
 */

/**
 * ${comment}
 */
export enum ${name} {
<#list values as value>
  /** ${value.comment} */
  ${value.name} = ${value.value?c}<#if value_has_next>,</#if>
</#list>
}
