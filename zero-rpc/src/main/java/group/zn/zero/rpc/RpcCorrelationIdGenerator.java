package group.zn.zero.rpc;

/**
 * RPC correlationId 生成器。
 *
 * <p>实现必须保证线程安全，并尽量避免在高频调用路径上使用阻塞、锁竞争或加密级随机数。</p>
 *
 * @author zn
 */
public interface RpcCorrelationIdGenerator {

    /**
     * 生成下一个 correlationId。
     *
     * @return correlationId；不可为空；同一生成器实例内不应重复；线程安全。
     */
    String nextCorrelationId();
}
