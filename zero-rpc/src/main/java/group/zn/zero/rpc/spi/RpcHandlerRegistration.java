package group.zn.zero.rpc.spi;

/** 精确路由注册所有权；线程安全、幂等关闭，只能删除该句柄注册的处理器。 */
@FunctionalInterface
public interface RpcHandlerRegistration extends AutoCloseable {
    /** 释放本次注册；被替换时无操作，失败允许重试且不能删除其他注册。 */
    @Override void close();
}
