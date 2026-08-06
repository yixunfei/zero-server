/*
 * ${generatedMarker}. Do not edit manually.
 */
package ${packageName};

<#list imports as import>
import ${import};
</#list>

/**
 * 生成协议定义注册入口。
 */
public final class GeneratedProtocolDefinitions {

    /**
     * 禁止实例化。
     */
    private GeneratedProtocolDefinitions() {
    }

    /**
     * 返回生成协议定义。
     *
     * @return 不可变、有序、可能为空、线程安全的协议定义列表。
     */
    public static List<ProtocolDefinition> definitions() {
<#if protocols?size == 0>
        return List.of();
<#else>
        return List.of(
<#list protocols as protocol>
                new ProtocolDefinition(
                        ${protocol.id?c},
                        "${protocol.name}",
                        ProtocolDirection.${protocol.direction},
                        ${protocol.version?c},
                        ${protocol.codecLiteral},
                        ${protocol.featuresLiteral})<#if protocol_has_next>,</#if>
</#list>
        );
</#if>
    }

    /**
     * 注册全部生成协议定义。
     *
     * @param registry 协议注册表；不可为空。
     * @throws NullPointerException 当协议注册表为空时抛出。
     */
    public static void registerTo(final ProtocolRegistry registry) {
        Objects.requireNonNull(registry, "registry");
        for (ProtocolDefinition definition : definitions()) {
            registry.register(definition);
        }
    }
}
