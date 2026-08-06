package group.zn.zero.rpc.discovery;

/**
 * RPC 服务实例解析器。
 *
 * <p>解析器只处理服务发现快照和路由 metadata，不发送 RPC 请求，不依赖具体注册中心实现。
 *
 * @author zn
 */
public interface RpcServiceResolver {

    /**
     * 解析并选择一个 RPC 服务实例。
     *
     * @param query 查询条件；不可为空。
     * @return 选择结果；不可为空；线程安全性由实现声明。
     * @throws group.zn.zero.core.error.ZeroException 无实例或解析失败时抛出，必须绑定 ErrorCode。
     */
    RpcServiceSelection resolve(RpcServiceQuery query);

    /**
     * 返回当前查询对应的实例快照。
     *
     * @param query 查询条件；不可为空。
     * @return 快照；不可为空；实例列表不可变、有序、可能为空、线程安全。
     */
    RpcServiceSnapshot snapshot(RpcServiceQuery query);
}
