/*
 * ${generatedMarker}. Do not edit manually.
 */
package ${packageName};

<#list imports as import>
import ${import};
</#list>
<#if imports?size gt 0>
</#if>
/**
 * 生成协议事件业务接口。
 */
public interface ${boName} {
<#list methods as method>

    /**
     * ${method.comment}
     *
     * @param request 协议请求消息；不可为空。
     */
    ${method.signature};
</#list>
}
