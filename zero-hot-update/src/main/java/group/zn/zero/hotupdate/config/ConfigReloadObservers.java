package group.zn.zero.hotupdate.config;

import java.util.List;
import java.util.Objects;

/**
 * 配置重载观察者组合工具。
 *
 * @author zn
 */
public final class ConfigReloadObservers {

    private ConfigReloadObservers() {
    }

    /**
     * 按顺序组合多个观察者。
     *
     * <p>任一观察者失败时立即向上抛出，后续观察者不再执行；异常不会被吞掉。
     *
     * @param observers 观察者列表；不可为空，调用后会复制。
     * @return 组合观察者；不可为空；线程安全性取决于子观察者。
     * @throws NullPointerException 当列表或列表元素为空时抛出。
     */
    public static ConfigReloadObserver composite(final List<? extends ConfigReloadObserver> observers) {
        List<ConfigReloadObserver> copied = List.copyOf(Objects.requireNonNull(observers, "observers"));
        copied.forEach(observer -> Objects.requireNonNull(observer, "observer"));
        return (request, result) -> {
            for (ConfigReloadObserver observer : copied) {
                observer.onReload(request, result);
            }
        };
    }
}
