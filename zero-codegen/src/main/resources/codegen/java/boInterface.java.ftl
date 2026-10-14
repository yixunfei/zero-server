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

    /** 类型化异步业务接口；R 由组合根绑定响应 DTO 或本地业务结果，不阻塞分发线程。
     * @param <R> 无显式响应声明时的结果类型。 */
    interface Async<R> {
<#list methods as method>
        /** ${method.comment} @return 实际业务完成阶段；失败保留原始原因。 */
        ${method.asyncSignature};
</#list>
    }
}
