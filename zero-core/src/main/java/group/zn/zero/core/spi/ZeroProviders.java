package group.zn.zero.core.spi;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.ServiceLoader;

/**
 * ZeroProvider 工具。
 *
 * @author zn
 */
public final class ZeroProviders {

    /**
     * 统一排序规则。
     */
    private static final Comparator<ZeroProvider> ORDER = Comparator
            .comparingInt(ZeroProvider::priority)
            .thenComparing(ZeroProviders::providerName)
            .thenComparing(provider -> provider.getClass().getName());

    private ZeroProviders() {
        // 工具类禁止实例化。
    }

    /**
     * 加载 SPI 提供者并按优先级排序。
     *
     * @param serviceType 服务类型；不可为空。
     * @param <T> 提供者类型。
     * @return 排序后的不可变提供者列表；可能为空；无承诺顺序之外的结构；线程安全。
     */
    public static <T extends ZeroProvider> List<T> load(final Class<T> serviceType) {
        List<T> providers = new ArrayList<>();
        for (T provider : ServiceLoader.load(serviceType)) {
            providers.add(provider);
        }
        return ordered(providers);
    }

    /**
     * 按优先级排序提供者。
     *
     * @param providers 提供者集合；不可为空。
     * @param <T> 提供者类型。
     * @return 排序后的不可变提供者列表；可能为空；线程安全。
     */
    public static <T extends ZeroProvider> List<T> ordered(final Collection<? extends T> providers) {
        List<T> ordered = new ArrayList<>(providers);
        ordered.sort((left, right) -> ORDER.compare(left, right));
        return List.copyOf(ordered);
    }

    /**
     * 返回优先级最高的提供者。
     *
     * @param providers 提供者集合；不可为空。
     * @param <T> 提供者类型。
     * @return 优先级最高的提供者；可能为空；线程安全。
     */
    public static <T extends ZeroProvider> Optional<T> first(final Collection<? extends T> providers) {
        List<T> ordered = ordered(providers);
        return ordered.isEmpty() ? Optional.empty() : Optional.of(ordered.get(0));
    }

    /**
     * 返回优先级最高的 SPI 提供者。
     *
     * @param serviceType 服务类型；不可为空。
     * @param <T> 提供者类型。
     * @return 优先级最高的提供者；可能为空；线程安全。
     */
    public static <T extends ZeroProvider> Optional<T> first(final Class<T> serviceType) {
        return first(load(serviceType));
    }

    private static String providerName(final ZeroProvider provider) {
        String name = provider.name();
        return name == null ? provider.getClass().getName() : name;
    }
}
