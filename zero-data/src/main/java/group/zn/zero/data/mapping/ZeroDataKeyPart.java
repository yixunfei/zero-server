package group.zn.zero.data.mapping;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 数据组合键字段声明。
 *
 * @author zn
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.RECORD_COMPONENT})
public @interface ZeroDataKeyPart {

    /**
     * 返回组合键字段顺序。
     *
     * @return 字段顺序；线程安全。
     */
    int order();

    /**
     * 返回组合键字段名。
     *
     * @return 字段名；为空时使用 Java 成员名；线程安全。
     */
    String name() default "";
}
