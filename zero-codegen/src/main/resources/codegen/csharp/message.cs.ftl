/*
 * ${generatedMarker}. Do not edit manually.
 */
using System;
using System.Collections.Generic;

namespace ${namespace};

/// <summary>
/// ${comment}
/// </summary>
public sealed class ${name} : IZeroGeneratedPayload
{
<#list fields as field>
    /// <summary>${field.comment}</summary>
    public ${field.type} ${field.name} { get; set; }<#if field.defaultValue?has_content> = ${field.defaultValue};</#if>

</#list>
}
