/*
 * ${generatedMarker}. Do not edit manually.
 */

/**
 * 生成协议号常量。
 */
export const ProtocolIds = {
  /** 最大协议号。 */
  MAX_ID: ${maxId?c}<#if items?size gt 0>,</#if>
<#list items as item>
  /** ${item.comment} */
  ${item.constantName}: ${item.id?c}<#if item_has_next>,</#if>
</#list>
} as const;
