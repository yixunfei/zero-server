package group.zn.zero.hotupdate.config;

/**
 * CSV 行到业务配置对象的转换器。
 *
 * <p>实现不应保存本次加载的可变共享状态。转换发生在受管 IO 执行域，
 * 不会进入业务 Actor 或 Netty IO 关键路径。
 *
 * @param <V> 业务配置对象类型。
 * @author zn
 */
@FunctionalInterface
public interface CsvRowDecoder<V> {

    /**
     * 转换单行 CSV 数据。
     *
     * @param row CSV 行；不可为空、不可变。
     * @return 业务配置对象；不可为空。
     * @throws Exception 当列值转换或业务构造失败时抛出；调用方会拒绝整表且保留旧快照。
     */
    V decode(CsvRow row) throws Exception;
}
