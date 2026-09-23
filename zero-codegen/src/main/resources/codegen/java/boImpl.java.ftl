/*
 * Created by zeroServer codegen. User-owned implementation; edit as needed.
 */
package ${packageName};

<#list imports as import>
import ${import};
</#list>
<#if imports?size gt 0>
</#if>
/**
 * 协议事件业务实现；仅首次生成，后续由业务开发者维护。
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
