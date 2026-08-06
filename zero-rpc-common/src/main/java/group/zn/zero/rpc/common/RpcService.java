package group.zn.zero.rpc.common;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * RPC 服务契约注解。
 *
 * <p>该注解只能放在 common 接口上，调用方通过接口代理调用，服务方实现同一接口。
 *
 * @author zn
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface RpcService {

    /**
     * 返回 RPC 服务名。
     *
     * @return 服务名；不可为空；线程安全。
     */
    String name();

    /**
     * 返回服务契约版本。
     *
     * @return 服务版本；必须大于 0；线程安全。
     */
    int version() default 1;

    /**
     * 返回默认 topic。
     *
     * @return topic；为空时由传输适配器或 topic resolver 生成；线程安全。
     */
    String topic() default "";

    /**
     * 返回默认 consumer group。
     *
     * @return consumer group；为空时由传输适配器或 group resolver 生成；线程安全。
     */
    String group() default "";

    /**
     * 返回服务说明。
     *
     * @return 服务说明；可为空；线程安全。
     */
    String description() default "";
}
