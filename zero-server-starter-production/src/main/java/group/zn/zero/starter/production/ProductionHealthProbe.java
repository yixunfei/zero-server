package group.zn.zero.starter.production;

import java.time.Duration;

/**
 * 接收剩余启动预算的 Adapter 健康探测。
 *
 * @author zn
 */
@FunctionalInterface
interface ProductionHealthProbe {

    /**
     * 在给定驱动原生 timeout 上限内执行一次启动探测。
     *
     * @param timeout 正数 timeout；不可为空。
     * @throws RuntimeException 探测失败时抛出；调用边界会转换为安全异常。
     */
    void check(Duration timeout);
}
