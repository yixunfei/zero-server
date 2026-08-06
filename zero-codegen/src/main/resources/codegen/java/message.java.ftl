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
 * ${comment}
 */
public final class ${name} implements Serializable, ZeroGeneratedPayload {

    /**
     * 序列化版本。
     */
    private static final long serialVersionUID = 1L;
<#list fields as field>

    /**
     * ${field.comment}
     */
    public ${field.type} ${field.name}<#if field.defaultValue?has_content> = ${field.defaultValue}</#if>;
</#list>
}
