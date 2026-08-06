package group.zn.zero.hotupdate.config;

/**
 * 配置文本值转换器。
 *
 * @param <T> 目标值类型。
 * @author zn
 */
@FunctionalInterface
public interface ConfigValueDecoder<T> {

    /**
     * 转换单个原始文本值。
     *
     * @param value 原始文本；不可为空。
     * @return 转换结果；不可为空。
     * @throws Exception 当格式非法或类型转换失败时抛出。
     */
    T decode(String value) throws Exception;
}
