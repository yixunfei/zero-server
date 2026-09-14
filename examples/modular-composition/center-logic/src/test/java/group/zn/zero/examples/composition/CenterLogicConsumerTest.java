package group.zn.zero.examples.composition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** 在独立 Maven 消费者中验证业务调用及可选依赖隔离。 */
class CenterLogicConsumerTest {
    @Test
    void centerAndLogicCommunicateWithoutMiddlewareOrStarters() {
        assertEquals(1, CenterLogicApplication.runDemo());
        assertEquals(1, CenterLogicApplication.runDemo());
        for (String absent : new String[] {"org.apache.kafka.clients.producer.KafkaProducer",
                "com.alibaba.nacos.api.NacosFactory", "redis.clients.jedis.RedisClient",
                "com.mongodb.client.MongoClient", "org.postgresql.Driver",
                "group.zn.zero.starter.LocalRuntime", "io.netty.channel.Channel"}) {
            assertThrows(ClassNotFoundException.class, () -> Class.forName(absent));
        }
    }
}
