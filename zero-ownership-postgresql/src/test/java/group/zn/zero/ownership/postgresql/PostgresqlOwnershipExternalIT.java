package group.zn.zero.ownership.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import group.zn.zero.ownership.CommitResult;
import group.zn.zero.ownership.MigrationId;
import group.zn.zero.ownership.MigrationCheckpointMetadata;
import group.zn.zero.ownership.MigrationRecord;
import group.zn.zero.ownership.MigrationState;
import group.zn.zero.ownership.OwnershipException;
import group.zn.zero.ownership.OwnershipLease;
import group.zn.zero.ownership.OwnershipResource;
import group.zn.zero.ownership.OwnershipRoute;
import group.zn.zero.ownership.ProcessIncarnation;
import group.zn.zero.ownership.StateCommitRequest;
import group.zn.zero.ownership.StateVersion;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

/** 使用显式外部 PostgreSQL 验证跨进程存储语义；未配置时整个类跳过。 */
class PostgresqlOwnershipExternalIT {
    private PostgresqlOwnershipDatabase database;
    private PostgresqlOwnershipStore first;
    private PostgresqlOwnershipStore second;
    private PostgresqlFencedStateStore<String> state;
    private PostgresqlMigrationStore migrations;
    private PostgresqlOwnershipRouter router;
    private String tablePrefix;

    /** 为每个测试建立隔离表前缀，避免依赖清理共享数据库。 */
    @BeforeEach
    void setUp() {
        Optional<ConnectionSettings> settings = ConnectionSettings.load();
        assumeTrue(settings.isPresent(), "external PostgreSQL settings are not configured");
        ConnectionSettings value = settings.orElseThrow();
        tablePrefix = "it_" + Long.toUnsignedString(System.nanoTime());
        database = new PostgresqlOwnershipDatabase(value.dataSource(), tablePrefix);
        first = new PostgresqlOwnershipStore(database);
        second = new PostgresqlOwnershipStore(database);
        state = new PostgresqlFencedStateStore<>(database, new StringCodec());
        migrations = new PostgresqlMigrationStore(database);
        router = new PostgresqlOwnershipRouter(database);
    }

    /** 两个独立 store 争抢同一资源时只有一个成功。 */
    @Test
    void competingStoresOnlyOneAcquires() throws Exception {
        OwnershipResource resource = resource("contend");
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> left = executor.submit(() -> acquire(start, first, resource, "p1"));
            Future<Boolean> right = executor.submit(() -> acquire(start, second, resource, "p2"));
            start.countDown();
            assertEquals(1, (left.get() ? 1 : 0) + (right.get() ? 1 : 0));
        } finally {
            executor.shutdownNow();
        }
    }

    /** 两个独立 JVM 同时争抢同一 PostgreSQL 资源时只有一个 owner 成功。 */
    @Test
    void independentProcessesOnlyOneAcquires() throws Exception {
        OwnershipResource resource = resource("process-contend");
        long startAt = System.currentTimeMillis() + 3_000;
        Process left = startProcess(resource, "process-left", startAt);
        Process right = startProcess(resource, "process-right", startAt);
        try {
            assertTrue(left.waitFor(15, TimeUnit.SECONDS), "left ownership process timed out");
            assertTrue(right.waitFor(15, TimeUnit.SECONDS), "right ownership process timed out");
            String leftOutput = output(left);
            String rightOutput = output(right);
            assertEquals(1, occurrences(leftOutput + rightOutput, "acquired"));
            assertEquals(1, occurrences(leftOutput + rightOutput, "contended"));
        } finally {
            stop(left);
            stop(right);
        }
    }

    /** 过期接管递增 epoch，旧 lease 的状态写入被拒绝。 */
    @Test
    void expiredLeaseIsFenced() throws InterruptedException {
        OwnershipResource resource = resource("fence");
        OwnershipLease oldLease = first.acquire(resource, incarnation("old"), Duration.ofMillis(150));
        Thread.sleep(250);
        OwnershipLease newLease = second.claim(resource, incarnation("new"), Duration.ofSeconds(5));
        assertEquals(oldLease.epoch() + 1, newLease.epoch());
        StateCommitRequest<String> stale = new StateCommitRequest<>(resource, oldLease,
                StateVersion.initial(), "stale", "old");
        assertThrows(OwnershipException.class, () -> state.commit(stale));
    }

    /** 状态版本 CAS 与 requestId 重放在数据库重连后的新 store 实例上保持一致。 */
    @Test
    void stateCasAndReplayAreDurable() {
        OwnershipResource resource = resource("state");
        OwnershipLease lease = first.acquire(resource, incarnation("writer"), Duration.ofSeconds(5));
        StateCommitRequest<String> request = new StateCommitRequest<>(resource, lease,
                StateVersion.initial(), "request-1", "value");
        CommitResult<String> committed = state.commit(request);
        PostgresqlFencedStateStore<String> restarted = new PostgresqlFencedStateStore<>(database, new StringCodec());
        CommitResult<String> replayed = restarted.commit(request);
        assertTrue(committed.committed());
        assertTrue(replayed.replayed());
        assertEquals(committed.newVersion(), replayed.newVersion());
        assertThrows(OwnershipException.class, () -> restarted.commit(new StateCommitRequest<>(resource, lease,
                StateVersion.initial(), "request-1", "different")));
        assertThrows(OwnershipException.class, () -> restarted.commit(new StateCommitRequest<>(resource, lease,
                StateVersion.initial(), "request-2", "second")));
    }

    /** 主动释放不能清除 fencing 历史；同一 incarnation 重获租约后旧 token 仍须被拒绝。 */
    @Test
    void releaseRetainsEpochAcrossReacquisition() {
        var resource = resource("release-epoch");
        var owner = incarnation("same-process");
        var oldLease = first.acquire(resource, owner, Duration.ofSeconds(30));
        assertTrue(first.release(oldLease));
        var newLease = second.acquire(resource, owner, Duration.ofSeconds(30));
        assertEquals(oldLease.epoch() + 1, newLease.epoch());
        assertThrows(OwnershipException.class, () -> first.assertCurrent(oldLease));
        assertThrows(OwnershipException.class, () -> state.commit(new StateCommitRequest<>(resource, oldLease,
                StateVersion.initial(), "stale-after-release", "old")));
        assertTrue(state.commit(new StateCommitRequest<>(resource, newLease,
                StateVersion.initial(), "new-after-release", "new")).committed());
    }

    /** 两个请求都完成首次去重查询后才竞争行锁；等待者必须重放已提交收据。 */
    @Test
    void concurrentIdenticalCommitsReplayAfterWaitingForLeaseLock() throws Exception {
        var resource = resource("concurrent-replay");
        var lease = first.acquire(resource, incarnation("concurrent-writer"), Duration.ofSeconds(30));
        var request = new StateCommitRequest<>(resource, lease, StateVersion.initial(), "same-request", "value");
        var reads = new CountDownLatch(2);
        var source = synchronizeReceiptReads(ConnectionSettings.load().orElseThrow().dataSource(), reads);
        var gatedDatabase = new PostgresqlOwnershipDatabase(source, tablePrefix);
        var gatedStore = new PostgresqlFencedStateStore<>(gatedDatabase, new StringCodec());
        try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
            Future<CommitResult<String>> left = workers.submit(() -> gatedStore.commit(request));
            Future<CommitResult<String>> right = workers.submit(() -> gatedStore.commit(request));
            var firstResult = left.get(10, TimeUnit.SECONDS);
            var secondResult = right.get(10, TimeUnit.SECONDS);
            assertEquals(1, (firstResult.committed() ? 1 : 0) + (secondResult.committed() ? 1 : 0));
            assertEquals(1, (firstResult.replayed() ? 1 : 0) + (secondResult.replayed() ? 1 : 0));
            assertEquals(firstResult.newVersion(), secondResult.newVersion());
            assertEquals(1, state.read(resource).orElseThrow().version().value());
        }
    }

    /** 在真实 JDBC 查询结束后设置屏障，保证首次去重读取都未见收据。 */
    private static DataSource synchronizeReceiptReads(final DataSource delegate, final CountDownLatch reads) {
        return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[] {DataSource.class},
                (proxy, method, args) -> {
                    Object result = invoke(delegate, method, args);
                    return result instanceof Connection connection ? synchronizeConnection(connection, reads) : result;
                });
    }

    private static Connection synchronizeConnection(final Connection delegate, final CountDownLatch reads) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                (proxy, method, args) -> {
                    Object result = invoke(delegate, method, args);
                    if (result instanceof PreparedStatement statement && args != null && args[0] instanceof String sql
                            && sql.startsWith("select expected_version")) {
                        return synchronizeStatement(statement, reads);
                    }
                    return result;
                });
    }

    private static PreparedStatement synchronizeStatement(final PreparedStatement delegate, final CountDownLatch reads) {
        return (PreparedStatement) Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                new Class<?>[] {PreparedStatement.class}, (proxy, method, args) -> {
                    Object result = invoke(delegate, method, args);
                    if (method.getName().equals("executeQuery")) {
                        reads.countDown();
                        if (!reads.await(5, TimeUnit.SECONDS)) throw new AssertionError("both receipt reads must finish");
                    }
                    return result;
                });
    }

    private static Object invoke(final Object target, final Method method, final Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }

    /** 迁移记录可由新的 store 实例读取并从 checkpoint 继续推进。 */
    @Test
    void migrationSurvivesStoreRestart() {
        OwnershipResource resource = resource("migration");
        ProcessIncarnation source = incarnation("source");
        ProcessIncarnation target = incarnation("target");
        MigrationId id = new MigrationId("migration-" + System.nanoTime());
        MigrationRecord record = new MigrationRecord(id, resource, source, target, MigrationState.REQUESTED,
                StateVersion.initial(), Instant.now(), new MigrationCheckpointMetadata("schema-2", "codec-7", false));
        migrations.create(record);
        migrations.transition(id, MigrationState.FROZEN, StateVersion.initial(), Instant.now());
        migrations.transition(id, MigrationState.CHECKPOINTED, new StateVersion(3), Instant.now());
        PostgresqlMigrationStore restarted = new PostgresqlMigrationStore(database);
        MigrationRecord recovered = restarted.find(id).orElseThrow();
        assertEquals(MigrationState.CHECKPOINTED, recovered.state());
        assertEquals(3, recovered.checkpointVersion().value());
        assertEquals("schema-2", recovered.checkpointMetadata().schemaVersion());
        assertEquals(MigrationState.COMMITTED,
                restarted.transition(id, MigrationState.COMMITTED, recovered.checkpointVersion(), Instant.now()).state());
    }

    /** 路由发布必须绑定当前 lease，过期或旧 epoch 不能覆盖新 owner。 */
    @Test
    void routeIsPersistedAndFencedByLease() {
        OwnershipResource resource = resource("route");
        OwnershipLease lease = first.acquire(resource, incarnation("route-owner"), Duration.ofSeconds(5));
        OwnershipRoute route = router.publish(lease, "rpc://route-owner");
        assertEquals("rpc://route-owner", router.resolve(resource).orElseThrow().endpoint());
        assertEquals(lease.epoch(), route.epoch());
        assertTrue(first.release(lease));
        assertTrue(router.resolve(resource).isEmpty());
        assertTrue(router.withdraw(lease));
        assertTrue(router.resolve(resource).isEmpty());
    }

    private static boolean acquire(final CountDownLatch start, final PostgresqlOwnershipStore store,
            final OwnershipResource resource, final String processId) throws InterruptedException {
        start.await(5, TimeUnit.SECONDS);
        try {
            store.acquire(resource, incarnation(processId), Duration.ofSeconds(5));
            return true;
        } catch (OwnershipException expected) {
            return false;
        }
    }

    private Process startProcess(final OwnershipResource resource, final String owner, final long startAt)
            throws IOException {
        ConnectionSettings settings = ConnectionSettings.load().orElseThrow();
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        String java = Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
        ProcessBuilder builder = new ProcessBuilder(java, "-cp", classpath,
                OwnershipProcessProbe.class.getName(), settings.url(), settings.user(), tablePrefix,
                resource.resourceType(), resource.resourceId(),
                Long.toString(resource.generation()), owner, Long.toString(startAt));
        builder.environment().put("ZERO_PROCESS_POSTGRES_PASSWORD", settings.password());
        builder.redirectErrorStream(true);
        return builder.start();
    }

    private static String output(final Process process) throws IOException {
        try (var stream = process.getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static int occurrences(final String text, final String value) {
        return (text.length() - text.replace(value, "").length()) / value.length();
    }

    private static void stop(final Process process) {
        if (process.isAlive()) {
            process.destroyForcibly();
        }
    }

    private static OwnershipResource resource(final String id) {
        return new OwnershipResource("integration", id + '-' + System.nanoTime(), 1);
    }

    private static ProcessIncarnation incarnation(final String id) {
        return new ProcessIncarnation("external-" + id, Long.toUnsignedString(System.nanoTime()));
    }

    /** 只承载字符串，避免外部测试引入业务序列化依赖。 */
    private static final class StringCodec implements OwnershipPayloadCodec<String> {
        @Override
        public byte[] encode(final String value) {
            return value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }

        @Override
        public String decode(final byte[] payload) {
            return payload == null ? null : new String(payload, java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    /** 外部测试配置；密码支持直接值或只读 secret 文件。 */
    private record ConnectionSettings(String url, String user, String password) {
        static Optional<ConnectionSettings> load() {
            String url = setting("zero.external.postgres.url", "ZERO_EXTERNAL_POSTGRES_URL");
            String user = setting("zero.external.postgres.user", "ZERO_EXTERNAL_POSTGRES_USER");
            String password = setting("zero.external.postgres.password", "ZERO_EXTERNAL_POSTGRES_PASSWORD");
            String passwordFile = setting("zero.external.postgres.passwordFile", "ZERO_EXTERNAL_POSTGRES_PASSWORD_FILE");
            if (password == null && passwordFile != null) {
                try {
                    password = Files.readString(Path.of(passwordFile)).trim();
                } catch (Exception failure) {
                    throw new IllegalStateException("cannot read configured PostgreSQL password file", failure);
                }
            }
            return url == null || user == null || password == null
                    ? Optional.empty() : Optional.of(new ConnectionSettings(url, user, password));
        }

        DataSource dataSource() {
            PGSimpleDataSource source = new PGSimpleDataSource();
            source.setURL(url);
            source.setUser(user);
            source.setPassword(password);
            source.setConnectTimeout(5);
            source.setSocketTimeout(10);
            return source;
        }

        private static String setting(final String property, final String environment) {
            String value = System.getProperty(property);
            return value == null || value.isBlank() ? System.getenv(environment) : value;
        }
    }
}
