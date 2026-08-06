package group.zn.zero.core.spi;

/**
 * SPI 服务提供者基础接口。
 *
 * @author zn
 */
public interface ZeroProvider {

    /**
     * 返回提供者名称。
     *
     * @return 提供者名称；不可为空；线程安全。
     */
    String name();

    /**
     * 返回提供者优先级。
     *
     * @return 数值越小优先级越高；线程安全。
     */
    default int priority() {
        return 0;
    }
}

