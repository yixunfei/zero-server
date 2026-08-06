package group.zn.zero.data;

import group.zn.zero.core.lifecycle.Lifecycle;

/**
 * 数据服务抽象。
 *
 * @author zn
 */
public interface DataService extends Lifecycle {

    /**
     * 返回数据服务名称。
     *
     * @return 服务名称；不可为空；线程安全。
     */
    String serviceName();
}

