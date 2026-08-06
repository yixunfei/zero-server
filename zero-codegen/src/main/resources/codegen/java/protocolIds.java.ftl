/*
 * ${generatedMarker}. Do not edit manually.
 */
package ${packageName};

/**
 * 生成协议号常量。
 */
public final class ProtocolIds {

    /**
     * 最大协议号。
     */
    public static final int MAX_ID = ${maxId?c};
<#list items as item>

    /**
     * ${item.comment}
     */
    public static final int ${item.constantName} = ${item.id?c};
</#list>

    /**
     * 禁止实例化。
     */
    private ProtocolIds() {
    }
}
