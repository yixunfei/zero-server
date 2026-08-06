package group.zn.zero.data.mapping;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 数据对象声明。
 *
 * @author zn
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ZeroDataObject {

    /**
     * 返回命名空间。
     *
     * @return 命名空间；不可为空；线程安全。
     */
    String namespace() default "default";

    /**
     * 返回集合名称。
     *
     * @return 集合名称；不可为空；线程安全。
     */
    String collection();

    /**
     * 返回 schema 版本。
     *
     * @return schema 版本；线程安全。
     */
    int schemaVersion() default 1;

    /**
     * 返回 key 前缀。
     *
     * @return key 前缀；可为空；线程安全。
     */
    String keyPrefix() default "";
}
