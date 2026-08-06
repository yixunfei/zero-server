package group.zn.zero.data.mapping;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 数据对象 ID 声明。
 *
 * @author zn
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.RECORD_COMPONENT})
public @interface ZeroDataId {

    /**
     * 返回数据键生成器类型。
     *
     * @return 数据键生成器类型；不可为空；线程安全。
     */
    Class<? extends ZeroDataKeyGenerator<?>> generator() default NoopZeroDataKeyGenerator.class;
}
