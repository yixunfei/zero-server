package group.zn.zero.rpc.kafka;

import java.util.Properties;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;

/**
 * Kafka RPC 原生客户端工厂。
 *
 * <p>该包级接口隔离 Kafka 客户端创建，生产路径使用 Apache Kafka 实现，测试路径可注入
 * 可控客户端验证构造回滚和关闭截止时间；不会暴露给 starter 或业务模块。</p>
 *
 * @author zn
 */
interface KafkaRpcClientFactory {

    /** Apache Kafka 客户端工厂单例。 */
    KafkaRpcClientFactory APACHE = new KafkaRpcClientFactory() {

        /**
         * 创建 Apache Kafka producer。
         *
         * @param properties producer 配置；不可为空。
         * @return producer；不可为空；线程安全。
         */
        @Override
        public Producer<String, byte[]> createProducer(final Properties properties) {
            return new KafkaProducer<>(properties);
        }

        /**
         * 创建 Apache Kafka consumer。
         *
         * @param properties consumer 配置；不可为空。
         * @return consumer；不可为空；线程不安全，仅归属 consumer worker。
         */
        @Override
        public Consumer<String, byte[]> createConsumer(final Properties properties) {
            return new KafkaConsumer<>(properties);
        }
    };

    /**
     * 创建 Kafka producer。
     *
     * @param properties producer 配置；不可为空。
     * @return producer；不可为空；线程安全。
     * @throws RuntimeException 当客户端创建失败时抛出；调用方必须转换成安全框架异常。
     */
    Producer<String, byte[]> createProducer(Properties properties);

    /**
     * 创建 Kafka consumer。
     *
     * @param properties consumer 配置；不可为空。
     * @return consumer；不可为空；线程不安全，仅归属 consumer worker。
     * @throws RuntimeException 当客户端创建失败时抛出；调用方必须转换成安全框架异常。
     */
    Consumer<String, byte[]> createConsumer(Properties properties);
}
