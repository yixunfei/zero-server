package group.zn.zero.data.mapping;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 数据对象组合键声明。
 *
 * @author zn
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ZeroDataCompositeKey {

    /**
     * 返回组合键编码器类型。
     *
     * @return 编码器类型；不可为空；线程安全。
     */
    Class<? extends ZeroDataKeyCodec<?>> codec() default DefaultZeroDataKeyCodec.class;
}
