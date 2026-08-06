package group.zn.zero.data.mapping;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 父对象拥有的子集合声明。
 *
 * @author zn
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.RECORD_COMPONENT})
public @interface ZeroDataOwnedCollection {

    /**
     * 返回子对象类型。
     *
     * @return 子对象类型；不可为空；线程安全。
     */
    Class<?> target();

    /**
     * 返回子集合名称。
     *
     * @return 子集合名称；为空时使用目标对象声明；线程安全。
     */
    String collection() default "";
}
