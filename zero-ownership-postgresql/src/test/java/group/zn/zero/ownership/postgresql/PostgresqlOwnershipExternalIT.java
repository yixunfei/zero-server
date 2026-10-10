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
    /** 每个用例独立的表前缀，同时传给跨进程探针。 */
    private String tablePrefix;

    /** 纳秒租约在访问数据库前拒绝，支持的租约按微秒持久化。 */
    @Test void rejectsSubMicrosecondLeaseBeforeIssuing() {
        var target = resource("precision");
        var owner = incarnation("precision");
        assertThrows(IllegalArgumentException.class, () -> first.acquire(target, owner, Duration.ofNanos(999)));
        assertTrue(first.current(target, Instant.now()).isEmpty());
        var lease = first.acquire(target, owner, Duration.ofSeconds(1));
        assertEquals(0, lease.expiresAt().getNano() % 1000);
    }

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
        assertTrue(first.release(lease));
        assertThrows(OwnershipException.class, () -> restarted.commit(request));
        assertEquals(committed.newVersion(), restarted.read(resource).orElseThrow().version());
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

    /** 同一 lease 的并发提交由 lease 行锁串行化，等待者取得锁后必须重放已提交收据。 */
    @Test
    void concurrentIdenticalCommitsReplayAfterWaitingForLeaseLock() throws Exception {
        var resource = resource("concurrent-replay");
        var lease = first.acquire(resource, incarnation("concurrent-writer"), Duration.ofSeconds(30));
        var request = new StateCommitRequest<>(resource, lease, StateVersion.initial(), "same-request", "value");
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
            Future<CommitResult<String>> left = workers.submit(() -> commitAfterStart(ready, start, request));
            Future<CommitResult<String>> right = workers.submit(() -> commitAfterStart(ready, start, request));
            assertTrue(ready.await(5, TimeUnit.SECONDS), "both commit workers must be ready");
            start.countDown();
            var firstResult = left.get(10, TimeUnit.SECONDS);
            var secondResult = right.get(10, TimeUnit.SECONDS);
            assertEquals(1, (firstResult.committed() ? 1 : 0) + (secondResult.committed() ? 1 : 0));
            assertEquals(1, (firstResult.replayed() ? 1 : 0) + (secondResult.replayed() ? 1 : 0));
            assertEquals(firstResult.newVersion(), secondResult.newVersion());
            assertEquals(1, state.read(resource).orElseThrow().version().value());
        }
    }

    /** 等待两个 worker 统一起跑后提交，迫使其竞争同一 lease 行锁。 */
    private CommitResult<String> commitAfterStart(final CountDownLatch ready, final CountDownLatch start,
            final StateCommitRequest<String> request)
            throws InterruptedException {
        ready.countDown();
        assertTrue(start.await(5, TimeUnit.SECONDS), "commit workers must be released");
        return state.commit(request);
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
        assertThrows(OwnershipException.class, () -> restarted.transition(id, MigrationState.CHECKPOINTED,
                new StateVersion(2), Instant.now()));
        assertThrows(OwnershipException.class, () -> restarted.transition(id, MigrationState.COMMITTED,
                new StateVersion(2), Instant.now()));
        assertEquals(recovered, restarted.find(id).orElseThrow());
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

    /** epoch 耗尽必须映射框架异常，且事务回滚不改变持久化权威值。 */
    @Test void exhaustedEpochFailsWithoutChangingAuthority() throws Exception {
        var resource = resource("epoch-max");
        var owner = incarnation("epoch-max");
        first.acquire(resource, owner, Duration.ofSeconds(30));
        try (var connection = database.connection();
                var statement = connection.prepareStatement("update " + database.ownershipTable()
                        + " set epoch=?, expires_at=clock_timestamp() - interval '1 second' where resource_id=?")) {
            statement.setLong(1, Long.MAX_VALUE);
            statement.setString(2, resource.resourceId());
            assertEquals(1, statement.executeUpdate());
        }
        var failure = assertThrows(OwnershipException.class, () -> first.acquire(resource, owner, Duration.ofSeconds(30)));
        assertTrue(failure.getCause() instanceof ArithmeticException);
        assertTrue(first.current(resource, Instant.now()).isEmpty());
        try (var connection = database.connection();
                var statement = connection.prepareStatement("select epoch from " + database.ownershipTable()
                        + " where resource_id=?")) {
            statement.setString(1, resource.resourceId());
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next());
                assertEquals(Long.MAX_VALUE, rows.getLong(1));
            }
        }
    }

    /** 所有 Adapter 事务的成功、重放与业务失败出口都恢复连接原状态。 */
    @Test void transactionsRestoreAutoCommitAndCreateUsesOneConnection() {
        var probe = new ConnectionStateProbe(ConnectionSettings.load().orElseThrow().dataSource());
        var tracked = new PostgresqlOwnershipDatabase(probe.dataSource(), tablePrefix);
        for (boolean initial : new boolean[]{true, false}) {
            probe.initialAutoCommit(initial);
            verifyTransactionState(probe, tracked, Boolean.toString(initial));
        }
    }

    private void verifyTransactionState(final ConnectionStateProbe probe,
            final PostgresqlOwnershipDatabase tracked, final String suffix) {
        var ownerStore = new PostgresqlOwnershipStore(tracked);
        var stateStore = new PostgresqlFencedStateStore<>(tracked, new StringCodec());
        var migrationStore = new PostgresqlMigrationStore(tracked);
        var routeStore = new PostgresqlOwnershipRouter(tracked);
        var resource = resource("transaction-" + suffix);
        var owner = incarnation("transaction");
        var lease = ownerStore.acquire(resource, owner, Duration.ofSeconds(30));
        assertThrows(OwnershipException.class, () -> ownerStore.acquire(resource, owner, Duration.ofSeconds(30)));
        lease = ownerStore.renew(lease, Duration.ofSeconds(30));
        ownerStore.assertCurrent(lease);
        var wrong = new OwnershipLease(resource, owner, lease.epoch() + 1, lease.expiresAt());
        assertThrows(OwnershipException.class, () -> ownerStore.renew(wrong, Duration.ofSeconds(30)));
        assertThrows(OwnershipException.class, () -> ownerStore.assertCurrent(wrong));
        var request = new StateCommitRequest<>(resource, lease, StateVersion.initial(), "receipt", "value");
        stateStore.commit(request);
        assertTrue(stateStore.commit(request).replayed());
        assertThrows(OwnershipException.class, () -> stateStore.commit(
                new StateCommitRequest<>(resource, wrong, StateVersion.initial(), "receipt", "value")));
        routeStore.publish(lease, "rpc://current");
        assertThrows(OwnershipException.class, () -> routeStore.publish(wrong, "rpc://stale"));
        var id = new MigrationId("transaction-" + suffix);
        var record = new MigrationRecord(id, resource, owner, incarnation("target"), MigrationState.REQUESTED,
                StateVersion.initial(), Instant.EPOCH);
        int beforeCreate = probe.opened();
        assertEquals(record, migrationStore.create(record));
        assertEquals(beforeCreate + 1, probe.opened());
        assertEquals(record, migrationStore.create(record));
        migrationStore.transition(id, MigrationState.FROZEN, new StateVersion(3), Instant.now());
        assertThrows(OwnershipException.class,
                () -> migrationStore.transition(id, MigrationState.CHECKPOINTED, new StateVersion(2), Instant.now()));
        var conflicting = new MigrationRecord(id, resource, incarnation("different"), incarnation("target"),
                MigrationState.REQUESTED, StateVersion.initial(), Instant.EPOCH);
        assertThrows(OwnershipException.class, () -> migrationStore.create(conflicting));
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
