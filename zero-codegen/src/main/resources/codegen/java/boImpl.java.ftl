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
 * 生成协议事件业务默认实现模板。
 */
public class ${implName} implements ${boName} {
<#list methods as method>

    /**
     * ${method.comment}
     *
     * @param request 协议请求消息；不可为空。
     */
    @Override
    public ${method.signature} {
        throw new UnsupportedOperationException("implement generated protocol event method: ${method.methodName}");
    }
</#list>
}
