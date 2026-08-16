package group.zn.zero.runtime.spi;

/**
 * 显式注册到 catalog 的 runtime component provider。
 *
 * <p>同一 provider 可被多个独立 runtime 调用；实现不得复用由某个 runtime 拥有的 lifecycle、
 * resource 或 mutable binding。并发构建支持由 provider 自身契约明确，默认不应假定 create 串行跨 runtime。</p>
 *
 * @author zn
 */
public interface RuntimeComponentProvider {

    /**
     * 返回无副作用、不可变 descriptor。
     *
     * @return descriptor；不可为空。
     */
    ComponentDescriptor descriptor();

    /**
     * 创建组件贡献；每个已获得资源必须立即登记。
     *
     * @param context 受限创建上下文；不可为空。
     * @return contribution；不可为空。
     * @throws Exception 创建失败时抛出；装配边界会安全归一化。
     */
    ComponentContribution create(ComponentCreationContext context) throws Exception;
}
