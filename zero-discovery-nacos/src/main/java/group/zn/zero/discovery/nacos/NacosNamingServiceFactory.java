package group.zn.zero.discovery.nacos;

import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.NamingService;
import java.util.Properties;

/**
 * Nacos 命名服务客户端创建工厂。
 *
 * <p>生产实现委托 Nacos SDK，包内测试可注入确定性的生命周期替身。
 *
 * @author zn
 */
@FunctionalInterface
interface NacosNamingServiceFactory {

    /**
     * 创建 Nacos 命名服务客户端。
     *
     * @param properties Nacos 客户端属性；不可为空；可变、无序、非线程安全。
     * @return 命名服务客户端；不可为空；线程安全性由 Nacos SDK 声明。
     * @throws NacosException 当 SDK 无法创建客户端时抛出。
     */
    NamingService create(Properties properties) throws NacosException;
}
