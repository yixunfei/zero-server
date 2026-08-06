package group.zn.zero.data.mapping;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 数据字段声明。
 *
 * @author zn
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.RECORD_COMPONENT})
public @interface ZeroDataField {

    /**
     * 返回字段顺序。
     *
     * @return 字段顺序；线程安全。
     */
    int order();

    /**
     * 返回存储字段名。
     *
     * @return 存储字段名；为空时使用 Java 成员名；线程安全。
     */
    String name() default "";

    /**
     * 返回是否允许为空。
     *
     * @return true 表示允许为空；线程安全。
     */
    boolean nullable() default false;
}
