package group.zn.zero.codegen.scaffold;

import group.zn.zero.runtime.api.ComponentId;
import group.zn.zero.runtime.capability.MavenCoordinate;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 脚手架组件的完整元数据；组件解析、依赖生成、装配表达式和配置样例共享此定义。
 *
 * @param id 稳定组件 ID。
 * @param providers 需要由能力模型解析的 Provider。
 * @param artifacts 组件额外 Maven 坐标；与 Provider 声明合并，不替换其依赖。
 * @param installExpression 运行时安装表达式；为空表示仅提供依赖或配置。
 * @param configDefaults 生成到应用默认配置的 key/value。
 * @param configExample 追加到 properties 示例的文本；支持 `${projectName}` 替换项目名。
 * @author zn
 */
public record ScaffoldComponentDefinition(
        String id,
        List<ComponentId> providers,
        List<MavenCoordinate> artifacts,
        String installExpression,
        Map<String, String> configDefaults,
        String configExample) {

    /**
     * 复制并冻结元数据；不创建 Provider，调用后可跨线程读取。
     * @throws NullPointerException 任一字段或集合元素为空时抛出。
     * @throws IllegalArgumentException ID 不合法时抛出。
     */
    public ScaffoldComponentDefinition {
        id = requireId(id);
        providers = List.copyOf(Objects.requireNonNull(providers, "providers"));
        artifacts = List.copyOf(Objects.requireNonNull(artifacts, "artifacts"));
        installExpression = Objects.requireNonNull(installExpression, "installExpression");
        configDefaults = Map.copyOf(Objects.requireNonNull(configDefaults, "configDefaults"));
        configExample = Objects.requireNonNull(configExample, "configExample");
    }

    /**
     * 创建仅含运行时安装表达式的定义；无副作用。
     * @param id 稳定组件 ID。
     * @param installExpression 可信 Java 表达式，空串表示无需安装。
     * @param providers 能力模型中声明的 Provider ID；可为空数组。
     * @return 不可变、线程安全的定义。
     * @throws NullPointerException 输入或 Provider ID 为空时抛出。
     * @throws IllegalArgumentException ID 不合法时抛出。
     */
    public static ScaffoldComponentDefinition runtime(
            final String id,
            final String installExpression,
            final ComponentId... providers) {
        return new ScaffoldComponentDefinition(id, List.of(providers), List.of(), installExpression,
                Map.of(), "");
    }

    private static String requireId(final String value) {
        String checked = Objects.requireNonNull(value, "id").trim();
        if (!checked.matches("[a-z][a-z0-9-]*")) {
            throw new IllegalArgumentException("component id must be lowercase kebab-case");
        }
        return checked;
    }
}
