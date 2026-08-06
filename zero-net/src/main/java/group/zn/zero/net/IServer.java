package group.zn.zero.net;

import group.zn.zero.core.lifecycle.Lifecycle;

/**
 * 网络服务器抽象。
 *
 * @author zn
 */
public interface IServer extends Lifecycle {

    /**
     * 返回监听地址。
     *
     * @return 监听地址；不可为空；线程安全性由实现声明。
     */
    String bindAddress();

    /**
     * 返回服务器类型。
     *
     * @return 服务器类型；不可为空；线程安全。
     */
    ServerType serverType();
}
