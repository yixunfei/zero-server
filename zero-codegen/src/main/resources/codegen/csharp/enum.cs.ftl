/*
 * ${generatedMarker}. Do not edit manually.
 */
namespace ${namespace};

/// <summary>
/// ${comment}
/// </summary>
public enum ${name}
{
<#list values as value>
    /// <summary>${value.comment}</summary>
    ${value.name} = ${value.value?c}<#if value_has_next>,</#if>
</#list>
}
