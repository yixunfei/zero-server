package group.zn.zero.ownership.postgresql;

import group.zn.zero.ownership.OwnershipException;
import group.zn.zero.ownership.OwnershipResource;
import group.zn.zero.ownership.ProcessIncarnation;
import java.time.Duration;
import org.postgresql.ds.PGSimpleDataSource;

/** 外部集成测试使用的独立 JVM ownership 争抢进程。 */
public final class OwnershipProcessProbe {
    private OwnershipProcessProbe() {
    }

    /** 连接测试数据库并在共同起跑时间尝试获取资源 ownership。 */
    public static void main(final String[] arguments) throws InterruptedException {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(arguments[0]);
        source.setUser(arguments[1]);
        source.setPassword(System.getenv("ZERO_PROCESS_POSTGRES_PASSWORD"));
        PostgresqlOwnershipDatabase database = new PostgresqlOwnershipDatabase(source, arguments[2]);
        OwnershipResource resource = new OwnershipResource(arguments[3], arguments[4], Long.parseLong(arguments[5]));
        ProcessIncarnation owner = new ProcessIncarnation(arguments[6], "independent-jvm");
        waitUntil(Long.parseLong(arguments[7]));
        try {
            new PostgresqlOwnershipStore(database).acquire(resource, owner, Duration.ofSeconds(10));
            System.out.println("acquired");
        } catch (OwnershipException expected) {
            System.out.println("contended");
        }
    }

    private static void waitUntil(final long targetMillis) throws InterruptedException {
        while (System.currentTimeMillis() < targetMillis) {
            Thread.sleep(Math.min(targetMillis - System.currentTimeMillis(), 20));
        }
    }
}
