package group.zn.zero.rpc.common;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * RPC 方法契约注解。
 *
 * <p>方法 ID 是服务内稳定主路由键，不依赖 Java 方法顺序或参数类型字符串。
 *
 * @author zn
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface RpcMethod {

    /**
     * 返回服务内稳定方法 ID。
     *
     * @return 方法 ID；必须大于 0；线程安全。
     */
    int id();

    /**
     * 返回诊断方法名。
     *
     * @return 方法名；为空时使用 Java 方法名；线程安全。
     */
    String name() default "";

    /**
     * 返回默认超时时间。
     *
     * @return 超时时间，单位毫秒；必须大于 0；线程安全。
     */
    long timeoutMillis() default 3000;

    /**
     * 返回调用模式。
     *
     * @return 调用模式；不可为空；线程安全。
     */
    RpcCallMode mode() default RpcCallMode.REQUEST_RESPONSE;

    /**
     * 返回业务方法是否可幂等重试。
     *
     * @return true 表示业务显式声明可幂等重试；线程安全。
     */
    boolean idempotent() default false;

    /**
     * 返回分区键字段表达式。
     *
     * @return 分区键表达式；可为空；线程安全。
     */
    String partitionKey() default "";

    /**
     * 返回方法说明。
     *
     * @return 方法说明；可为空；线程安全。
     */
    String description() default "";
}
