package group.zn.zero.data.mapping;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 数据对象引用列表声明。
 *
 * @author zn
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.RECORD_COMPONENT})
public @interface ZeroDataReferenceList {

    /**
     * 返回目标对象类型。
     *
     * @return 目标对象类型；不可为空；线程安全。
     */
    Class<?> target();

    /**
     * 返回目标集合名称。
     *
     * @return 目标集合名称；为空时使用目标对象声明；线程安全。
     */
    String collection() default "";
}
