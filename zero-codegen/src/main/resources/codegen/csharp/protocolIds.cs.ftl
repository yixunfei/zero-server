/*
 * ${generatedMarker}. Do not edit manually.
 */
namespace ${namespace};

/// <summary>
/// 生成协议号常量。
/// </summary>
public static class ProtocolIds
{
    /// <summary>最大协议号。</summary>
    public const int MAX_ID = ${maxId?c};
<#list items as item>

    /// <summary>${item.comment}</summary>
    public const int ${item.constantName} = ${item.id?c};
</#list>
}
