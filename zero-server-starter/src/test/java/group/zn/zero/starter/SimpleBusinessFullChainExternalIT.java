package group.zn.zero.starter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mongodb.client.MongoClient;
import group.zn.zero.cache.CachePolicy;
import group.zn.zero.cache.CacheValueCodec;
import group.zn.zero.core.config.MapZeroConfig;
import group.zn.zero.core.error.SystemErrorCode;
import group.zn.zero.data.envelope.ZeroDataEnvelopeCrudRepository;
import group.zn.zero.data.mapping.ZeroDataField;
import group.zn.zero.data.mapping.ZeroDataId;
import group.zn.zero.data.mapping.ZeroDataMappingIntrospector;
import group.zn.zero.data.mapping.ZeroDataObject;
import group.zn.zero.data.mapping.ZeroDataVersion;
import group.zn.zero.data.model.VersionedEntity;
import group.zn.zero.data.mongo.MongoDataAdapter;
import group.zn.zero.data.mongo.MongoDataHealthCheck;
import group.zn.zero.data.mongo.MongoDriverSettings;
import group.zn.zero.data.postgresql.PostgresqlDataAdapter;
import group.zn.zero.data.postgresql.PostgresqlDataHealthCheck;
import group.zn.zero.data.postgresql.PostgresqlDriverSettings;
import group.zn.zero.data.redis.DefaultRedisCacheKeyStrategy;
import group.zn.zero.data.redis.RedisCacheEnvelopeCodec;
import group.zn.zero.data.redis.RedisCacheStore;
import group.zn.zero.data.redis.RedisDataAdapter;
import group.zn.zero.data.redis.RedisDataHealthCheck;
import group.zn.zero.data.redis.RedisDistributedCacheService;
import group.zn.zero.data.redis.RedisDriverSettings;
import group.zn.zero.discovery.nacos.NacosDiscoveryConfigKeys;
import group.zn.zero.discovery.nacos.NacosDiscoveryFactory;
import group.zn.zero.discovery.nacos.NacosDiscoverySettings;
import group.zn.zero.discovery.nacos.NacosHealthUpdateMode;
import group.zn.zero.discovery.nacos.ServiceDiscovery;
import group.zn.zero.discovery.nacos.ServiceDiscoveryConstants;
import group.zn.zero.discovery.nacos.ServiceInstance;
import group.zn.zero.discovery.nacos.ServiceQuery;
import group.zn.zero.log.InMemoryLogSink;
import group.zn.zero.log.LogAppender;
import group.zn.zero.log.LogLevel;
import group.zn.zero.log.LogOperation;
import group.zn.zero.log.LogResult;
import group.zn.zero.log.LogSource;
import group.zn.zero.log.LogType;
import group.zn.zero.log.ZeroLogRecord;
import group.zn.zero.monitor.MetricDefinition;
import group.zn.zero.monitor.MetricSample;
import group.zn.zero.monitor.MonitorRuntime;
import group.zn.zero.protocol.ProtocolDefinition;
import group.zn.zero.protocol.ProtocolDirection;
import group.zn.zero.protocol.buffer.ZeroReader;
import group.zn.zero.protocol.buffer.ZeroWriter;
import group.zn.zero.protocol.codec.GeneratedProtocolCodec;
import group.zn.zero.protocol.codec.ZeroPayloadCodec;
import group.zn.zero.rpc.RpcCallOptions;
import group.zn.zero.rpc.client.RpcClientFactory;
import group.zn.zero.rpc.codec.RpcCodecRegistry;
import group.zn.zero.rpc.common.RpcMethod;
import group.zn.zero.rpc.common.RpcResult;
import group.zn.zero.rpc.common.RpcService;
import group.zn.zero.rpc.discovery.RpcDiscoveryMetadata;
import group.zn.zero.rpc.kafka.KafkaRpcAdapter;
import group.zn.zero.rpc.kafka.KafkaRpcSettings;
import group.zn.zero.rpc.kafka.KafkaRpcTopicResolver;
import group.zn.zero.rpc.server.RpcServiceBinder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.RedisClient;

/**
 * Docker 真实环境下的简单业务全链路外部测试。
 *
 * @author zn
 */
class SimpleBusinessFullChainExternalIT {

    /**
     * external-tests profile 标记。
     */
    private static final String EXTERNAL_TESTS_ENABLED = "zero.external.tests";

    /**
     * Kafka broker 地址系统属性。
     */
    private static final String KAFKA_BOOTSTRAP_PROPERTY = "zero.kafka.bootstrapServers";

    /**
     * Kafka broker 地址环境变量。
     */
    private static final String KAFKA_BOOTSTRAP_ENV = "ZERO_KAFKA_BOOTSTRAP_SERVERS";

    /**
     * 业务登录指标名称。
     */
    private static final String LOGIN_METRIC = "zero_business_login_total";

    /**
     * 等待外部组件最终一致状态的超时时间。
     */
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(30);

    /**
     * 验证 Nacos、Kafka、MongoDB、Redis、PostgreSQL、日志和指标可以组成简单业务全链路。
     *
     * @throws Exception 当等待外部组件状态失败时抛出。
     */
    @Test
    void dockerRealEnvironmentShouldRunSimpleBusinessFullChain() throws Exception {
        assertTrue(Boolean.getBoolean(EXTERNAL_TESTS_ENABLED), "external-tests profile must be enabled");
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String serviceName = "external.business.player";
        String routeServiceName = serviceName + ":v1";
        String topicPrefix = "zero.rpc.business." + suffix;
        String providerGroup = "business-provider-" + suffix;
        String replyTopic = "business-reply-" + suffix;
        String instanceId = "business-provider-" + suffix;
        String playerId = "player-10086-" + suffix;
        String accountId = "account-10086-" + suffix;
        InMemoryLogSink baseLogSink = new InMemoryLogSink();
        MonitorRuntime monitorRuntime = MonitorRuntime.createDefault();
        ZeroRuntimeComponents components = ZeroRuntimeFactory.localBuilder(
                new MapZeroConfig(Map.of(
                        ZeroRuntimeConfigKeys.ZERO_MODE, "external-test",
                        ZeroRuntimeConfigKeys.ZERO_NAME, "docker-full-chain")),
                baseLogSink)
                .monitorRuntime(monitorRuntime)
                .build();
        ZeroServerApplication application = new ZeroServerApplication(components);
        MongoClient mongoClient = null;
        RedisClient redisClient = null;
        ServiceDiscovery discovery = null;
        KafkaRpcAdapter provider = null;
        KafkaRpcAdapter caller = null;
        try {
            application.start();
            components.monitorRuntime().registry().register(new MetricDefinition(
                    LOGIN_METRIC,
                    "simple business login count",
                    "count",
                    List.of("operation", "zone")));
            MongoDriverSettings mongoSettings = mongoSettings();
            MongoDataAdapter mongoAdapter = new MongoDataAdapter();
            mongoClient = mongoAdapter.createClient(mongoSettings);
            assertTrue(new MongoDataHealthCheck(mongoClient, mongoSettings.databaseName()).check(),
                    "MongoDB external service is unavailable");
            mongoClient.getDatabase(mongoSettings.databaseName()).getCollection("game__player_full_chain").drop();
            ZeroDataEnvelopeCrudRepository<String, PlayerProfile> playerRepository =
                    mongoAdapter.registerDriverRepository(
                            "player_full_chain",
                            mongoClient,
                            mongoSettings.databaseName(),
                            new ZeroDataMappingIntrospector().inspect(PlayerProfile.class),
                            PlayerProfileCodec.INSTANCE,
                            1);

            PostgresqlDriverSettings postgresqlSettings = postgresqlSettings();
            assertTrue(new PostgresqlDataHealthCheck(postgresqlSettings).check(),
                    "PostgreSQL external service is unavailable");
            ZeroDataEnvelopeCrudRepository<String, AccountProfile> accountRepository =
                    new PostgresqlDataAdapter().registerDriverRepository(
                            "account_full_chain",
                            postgresqlSettings,
                            new ZeroDataMappingIntrospector().inspect(AccountProfile.class),
                            AccountProfileCodec.INSTANCE,
                            1);
            accountRepository.deleteById(accountId).toCompletableFuture().join();

            RedisDriverSettings redisSettings = RedisDriverSettings.fromSystemProperties();
            RedisDataAdapter redisDataAdapter = new RedisDataAdapter();
            redisClient = redisDataAdapter.createClient(redisSettings);
            assertTrue(new RedisDataHealthCheck(redisClient).check(), "Redis external service is unavailable");
            redisClient.flushDB();
            RedisDistributedCacheService<String, String> cacheService =
                    new RedisDistributedCacheService<>(
                            "redis-full-chain",
                            CachePolicy.defaults(),
                            new RedisCacheStore<>(
                                    redisClient,
                                    "game",
                                    "player_login_full_chain",
                                    1,
                                    new DefaultRedisCacheKeyStrategy(),
                                    key -> key,
                                    StringCacheValueCodec.INSTANCE,
                                    new RedisCacheEnvelopeCodec()));

            discovery = NacosDiscoveryFactory.nacos(nacosSettings());
            discovery.start();
            provider = new KafkaRpcAdapter(kafkaSettings(
                    "business-provider-" + suffix,
                    providerGroup,
                    topicPrefix,
                    "provider-reply-" + suffix));
            caller = new KafkaRpcAdapter(kafkaSettings(
                    "business-caller-" + suffix,
                    "business-caller-" + suffix,
                    topicPrefix,
                    replyTopic));
            new RpcServiceBinder(provider, codecRegistry()).bind(BusinessPlayerRpc.class, new BusinessPlayerRpcImpl(
                    playerRepository,
                    accountRepository,
                    cacheService,
                    components.logAppender(),
                    components.monitorRuntime()));
            discovery.register(new ServiceInstance(
                    serviceName,
                    instanceId,
                    "127.0.0.1",
                    19092,
                    ServiceDiscoveryConstants.DEFAULT_GROUP_NAME,
                    ServiceDiscoveryConstants.DEFAULT_CLUSTER_NAME,
                    true,
                    true,
                    true,
                    1.0D,
                    0.1D,
                    RpcDiscoveryMetadata.providerMetadata(
                            serviceName,
                            "1.0.0",
                            new KafkaRpcTopicResolver(topicPrefix).requestTopic(routeServiceName),
                            providerGroup,
                            instanceId,
                            "kafka")));
            ServiceQuery query = new ServiceQuery(
                    serviceName,
                    ServiceDiscoveryConstants.DEFAULT_GROUP_NAME,
                    java.util.List.of(ServiceDiscoveryConstants.DEFAULT_CLUSTER_NAME),
                    false,
                    true);
            ServiceInstance instance = awaitInstance(discovery, query, instanceId);
            assertEquals("kafka", instance.metadata().get(RpcDiscoveryMetadata.PROTOCOL));

            BusinessPlayerRpc client = new RpcClientFactory(
                    caller,
                    codecRegistry(),
                    RpcCallOptions.defaults()
                            .withReplyTopic(replyTopic)
                            .withTraceId("trace-business-full-chain"))
                    .create(BusinessPlayerRpc.class);
            LoginResponseDTO response = client.login(new LoginRequestDTO(10086L, accountId, playerId, "zone-a"))
                    .orThrow();

            assertEquals(10086L, response.uid());
            assertEquals("player-10086", response.name());
            assertEquals("zone-a", response.zone());
            assertEquals("created", response.state());
            assertEquals(response.name(), cacheService.get("player:" + response.uid()).toCompletableFuture()
                    .join()
                    .orElseThrow());
            assertEquals(response.name(), playerRepository.findById(playerId).toCompletableFuture()
                    .join()
                    .orElseThrow()
                    .name());
            assertEquals(10086L, accountRepository.findById(accountId).toCompletableFuture()
                    .join()
                    .orElseThrow()
                    .uid());
            assertTrue(baseLogSink.records().stream()
                    .anyMatch(record -> "player login completed".equals(record.message())
                            && "10086".equals(record.fields().get("uid"))));
            assertTrue(components.monitorRuntime().registry().samples().stream()
                    .anyMatch(sample -> LOGIN_METRIC.equals(sample.name())
                            && "login".equals(sample.labels().get("operation"))));
        } finally {
            if (caller != null) {
                caller.close();
            }
            if (provider != null) {
                provider.close();
            }
            if (discovery != null && discovery.running()) {
                discovery.stop();
            }
            if (redisClient != null) {
                redisClient.close();
            }
            if (mongoClient != null) {
                mongoClient.close();
            }
            if (application.running()) {
                application.stop();
            }
        }
    }

    private ServiceInstance awaitInstance(
            final ServiceDiscovery discovery,
            final ServiceQuery query,
            final String instanceId) throws InterruptedException {
        ServiceInstance[] found = new ServiceInstance[1];
        await(() -> {
            found[0] = discovery.lookup(query).stream()
                    .filter(instance -> instance.instanceId().equals(instanceId))
                    .findFirst()
                    .orElse(null);
            return found[0] != null;
        });
        return found[0];
    }

    private void await(final BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + WAIT_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(200L);
        }
        assertTrue(condition.getAsBoolean(), "condition not met within " + WAIT_TIMEOUT);
    }

    private MongoDriverSettings mongoSettings() {
        return new MongoDriverSettings(
                setting("zero.mongo.uri", "ZERO_MONGO_URI", MongoDriverSettings.DEFAULT_CONNECTION_STRING),
                setting("zero.mongo.database", "ZERO_MONGO_DATABASE", MongoDriverSettings.DEFAULT_DATABASE_NAME));
    }

    private PostgresqlDriverSettings postgresqlSettings() {
        return new PostgresqlDriverSettings(
                requiredSetting(PostgresqlDriverSettings.JDBC_URL_PROPERTY, "ZERO_POSTGRESQL_URL"),
                requiredSetting(PostgresqlDriverSettings.USERNAME_PROPERTY, "ZERO_POSTGRES_USER"),
                requiredSetting(PostgresqlDriverSettings.PASSWORD_PROPERTY, "ZERO_POSTGRES_PASSWORD"),
                setting(PostgresqlDriverSettings.TABLE_NAME_PROPERTY,
                        "ZERO_POSTGRESQL_TABLE",
                        PostgresqlDriverSettings.DEFAULT_TABLE_NAME));
    }

    private NacosDiscoverySettings nacosSettings() {
        return new NacosDiscoverySettings(
                requiredSetting(
                        NacosDiscoveryConfigKeys.SYSTEM_SERVER_ADDR,
                        NacosDiscoveryConfigKeys.ENV_SERVER_ADDR),
                setting(NacosDiscoveryConfigKeys.SYSTEM_NAMESPACE,
                        NacosDiscoveryConfigKeys.ENV_NAMESPACE,
                        NacosDiscoverySettings.DEFAULT_NAMESPACE),
                setting(NacosDiscoveryConfigKeys.SYSTEM_USERNAME, NacosDiscoveryConfigKeys.ENV_USERNAME, ""),
                setting(NacosDiscoveryConfigKeys.SYSTEM_PASSWORD, NacosDiscoveryConfigKeys.ENV_PASSWORD, ""),
                setting(NacosDiscoveryConfigKeys.SYSTEM_ACCESS_KEY, NacosDiscoveryConfigKeys.ENV_ACCESS_KEY, ""),
                setting(NacosDiscoveryConfigKeys.SYSTEM_SECRET_KEY, NacosDiscoveryConfigKeys.ENV_SECRET_KEY, ""),
                ServiceDiscoveryConstants.DEFAULT_GROUP_NAME,
                ServiceDiscoveryConstants.DEFAULT_CLUSTER_NAME,
                10_000,
                false,
                NacosHealthUpdateMode.REREGISTER,
                Map.of());
    }

    private KafkaRpcSettings kafkaSettings(
            final String clientId,
            final String consumerGroupId,
            final String topicPrefix,
            final String replyTopic) {
        return new KafkaRpcSettings(
                requiredSetting(KAFKA_BOOTSTRAP_PROPERTY, KAFKA_BOOTSTRAP_ENV),
                "zero-full-chain-" + clientId,
                consumerGroupId,
                topicPrefix,
                replyTopic,
                128,
                Duration.ofMillis(100),
                Duration.ofSeconds(5),
                Map.of(),
                Map.of(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"));
    }

    private String requiredSetting(final String property, final String environment) {
        String current = setting(property, environment, null);
        assertNotNull(current, property + " or " + environment + " must be set");
        return current;
    }

    private String setting(final String property, final String environment, final String defaultValue) {
        return Optional.ofNullable(System.getProperty(property))
                .or(() -> Optional.ofNullable(System.getenv(environment)))
                .filter(value -> !value.isBlank())
                .orElse(defaultValue);
    }

    private RpcCodecRegistry codecRegistry() {
        RpcCodecRegistry registry = new RpcCodecRegistry();
        registry.register(
                LoginRequestDTO.class,
                new ProtocolDefinition(9201, "business.login.request", ProtocolDirection.CLIENT_TO_SERVER, 1),
                new GeneratedProtocolCodec<>(LoginRequestCodec.INSTANCE));
        registry.register(
                LoginResponseDTO.class,
                new ProtocolDefinition(9202, "business.login.response", ProtocolDirection.SERVER_TO_CLIENT, 1),
                new GeneratedProtocolCodec<>(LoginResponseCodec.INSTANCE));
        return registry;
    }

    /**
     * 简单业务玩家 RPC。
     *
     * @author zn
     */
    @RpcService(name = "external.business.player", version = 1)
    interface BusinessPlayerRpc {

        /**
         * 玩家登录。
         *
         * @param request 登录请求；不可为空。
         * @return 登录响应；不可为空；线程安全。
         */
        @RpcMethod(id = 1001, timeoutMillis = 10000, partitionKey = "playerId")
        RpcResult<LoginResponseDTO> login(LoginRequestDTO request);
    }

    /**
     * 简单业务玩家 RPC 实现。
     *
     * @author zn
     */
    static final class BusinessPlayerRpcImpl implements BusinessPlayerRpc {

        /**
         * 玩家档案仓库。
         */
        private final ZeroDataEnvelopeCrudRepository<String, PlayerProfile> playerRepository;

        /**
         * 账号仓库。
         */
        private final ZeroDataEnvelopeCrudRepository<String, AccountProfile> accountRepository;

        /**
         * Redis 缓存服务。
         */
        private final RedisDistributedCacheService<String, String> cacheService;

        /**
         * 安全日志写入端口。
         */
        private final LogAppender logAppender;

        /**
         * 监控运行时。
         */
        private final MonitorRuntime monitorRuntime;

        /**
         * 创建简单业务玩家 RPC 实现。
         *
         * @param playerRepository 玩家档案仓库；不可为空。
         * @param accountRepository 账号仓库；不可为空。
         * @param cacheService Redis 缓存服务；不可为空。
         * @param logAppender 安全日志写入端口；不可为空。
         * @param monitorRuntime 监控运行时；不可为空。
         */
        BusinessPlayerRpcImpl(
                final ZeroDataEnvelopeCrudRepository<String, PlayerProfile> playerRepository,
                final ZeroDataEnvelopeCrudRepository<String, AccountProfile> accountRepository,
                final RedisDistributedCacheService<String, String> cacheService,
                final LogAppender logAppender,
                final MonitorRuntime monitorRuntime) {
            this.playerRepository = java.util.Objects.requireNonNull(playerRepository, "playerRepository");
            this.accountRepository = java.util.Objects.requireNonNull(accountRepository, "accountRepository");
            this.cacheService = java.util.Objects.requireNonNull(cacheService, "cacheService");
            this.logAppender = java.util.Objects.requireNonNull(logAppender, "logAppender");
            this.monitorRuntime = java.util.Objects.requireNonNull(monitorRuntime, "monitorRuntime");
        }

        /**
         * 玩家登录。
         *
         * @param request 登录请求；不可为空。
         * @return 登录响应；不可为空；线程安全。
         */
        @Override
        public RpcResult<LoginResponseDTO> login(final LoginRequestDTO request) {
            try {
                AccountProfile account = new AccountProfile(request.accountId(), 0L, request.uid(), "docker");
                PlayerProfile profile = new PlayerProfile(
                        request.playerEntityId(),
                        0L,
                        request.uid(),
                        "player-" + request.uid(),
                        request.zone());
                accountRepository.save(account).toCompletableFuture().join();
                playerRepository.save(profile).toCompletableFuture().join();
                PlayerProfile saved = playerRepository.findById(profile.id()).toCompletableFuture()
                        .join()
                        .orElseThrow();
                cacheService.putVersioned("player:" + request.uid(), saved.name(), saved.version())
                        .toCompletableFuture()
                        .join();
                logAppender.append(ZeroLogRecord.create(
                        Instant.now(),
                        LogLevel.INFO,
                        LogType.BUSINESS,
                        new LogSource("simple-business", "external-test", "zero-server-starter"),
                        new LogOperation("player-login", LogResult.SUCCESS, null),
                        "trace-business-full-chain",
                        "player login completed",
                        Map.of(
                                "uid", Long.toString(request.uid()),
                                "zone", request.zone())));
                monitorRuntime.registry().record(new MetricSample(
                        LOGIN_METRIC,
                        1.0D,
                        Map.of("operation", "login", "zone", request.zone()),
                        Instant.now()));
                return RpcResult.success(new LoginResponseDTO(
                        saved.uid(),
                        saved.name(),
                        saved.zone(),
                        "created"));
            } catch (CompletionException ex) {
                return RpcResult.failure(SystemErrorCode.SYSTEM_ERROR, ex.getMessage());
            } catch (RuntimeException ex) {
                return RpcResult.failure(SystemErrorCode.SYSTEM_ERROR, ex.getMessage());
            }
        }
    }

    /**
     * 登录请求 DTO。
     *
     * @param uid 玩家 ID。
     * @param accountId 账号 ID。
     * @param playerEntityId 玩家实体 ID。
     * @param zone 分区名称。
     * @author zn
     */
    record LoginRequestDTO(long uid, String accountId, String playerEntityId, String zone) {

        /**
         * 返回分区键。
         *
         * @return 玩家 ID。
         */
        long playerId() {
            return uid;
        }
    }

    /**
     * 登录响应 DTO。
     *
     * @param uid 玩家 ID。
     * @param name 玩家名。
     * @param zone 分区名称。
     * @param state 业务状态。
     * @author zn
     */
    record LoginResponseDTO(long uid, String name, String zone, String state) {
    }

    /**
     * 玩家档案。
     *
     * @param id 玩家档案 ID。
     * @param version 版本号。
     * @param uid 玩家 ID。
     * @param name 玩家名。
     * @param zone 分区名称。
     * @author zn
     */
    @ZeroDataObject(namespace = "game", collection = "player_full_chain", schemaVersion = 1)
    record PlayerProfile(
            @ZeroDataId String id,
            @ZeroDataVersion long version,
            @ZeroDataField(order = 1, name = "uid") long uid,
            @ZeroDataField(order = 2, name = "name") String name,
            @ZeroDataField(order = 3, name = "zone") String zone) implements VersionedEntity<String> {

        /**
         * 返回指定版本的新档案。
         *
         * @param version 新版本号。
         * @return 新档案；不可为空。
         */
        @Override
        public PlayerProfile withVersion(final long version) {
            return new PlayerProfile(id, version, uid, name, zone);
        }
    }

    /**
     * 账号档案。
     *
     * @param id 账号 ID。
     * @param version 版本号。
     * @param uid 玩家 ID。
     * @param channel 渠道。
     * @author zn
     */
    @ZeroDataObject(namespace = "platform", collection = "account_full_chain", schemaVersion = 1)
    record AccountProfile(
            @ZeroDataId String id,
            @ZeroDataVersion long version,
            @ZeroDataField(order = 1, name = "uid") long uid,
            @ZeroDataField(order = 2, name = "channel") String channel) implements VersionedEntity<String> {

        /**
         * 返回指定版本的新账号档案。
         *
         * @param version 新版本号。
         * @return 新账号档案；不可为空。
         */
        @Override
        public AccountProfile withVersion(final long version) {
            return new AccountProfile(id, version, uid, channel);
        }
    }

    /**
     * 登录请求 codec。
     *
     * @author zn
     */
    static final class LoginRequestCodec implements ZeroPayloadCodec<LoginRequestDTO> {

        /**
         * 单例。
         */
        private static final LoginRequestCodec INSTANCE = new LoginRequestCodec();

        private LoginRequestCodec() {
        }

        /**
         * 返回 codec 名称。
         *
         * @return 名称；不可为空；线程安全。
         */
        @Override
        public String name() {
            return "business-login-request";
        }

        /**
         * 返回消息类型。
         *
         * @return 消息类型；不可为空；线程安全。
         */
        @Override
        public Class<LoginRequestDTO> messageType() {
            return LoginRequestDTO.class;
        }

        /**
         * 写入请求。
         *
         * @param writer 写入器；不可为空。
         * @param message 消息；不可为空。
         */
        @Override
        public void write(final ZeroWriter writer, final LoginRequestDTO message) {
            int marker = writer.beginObject();
            writer.writeLong(message.uid());
            writer.writeString(message.accountId());
            writer.writeString(message.playerEntityId());
            writer.writeString(message.zone());
            writer.endObject(marker);
        }

        /**
         * 读取请求。
         *
         * @param reader 读取器；不可为空。
         * @return 请求；不可为空；线程不安全。
         */
        @Override
        public LoginRequestDTO read(final ZeroReader reader) {
            int end = reader.beginObject();
            long uid = reader.hasRemainingInObject(end) ? reader.readLong() : 0L;
            String accountId = reader.hasRemainingInObject(end) ? reader.readString() : "";
            String playerEntityId = reader.hasRemainingInObject(end) ? reader.readString() : "";
            String zone = reader.hasRemainingInObject(end) ? reader.readString() : "";
            reader.endObject(end);
            return new LoginRequestDTO(uid, accountId, playerEntityId, zone);
        }
    }

    /**
     * 登录响应 codec。
     *
     * @author zn
     */
    static final class LoginResponseCodec implements ZeroPayloadCodec<LoginResponseDTO> {

        /**
         * 单例。
         */
        private static final LoginResponseCodec INSTANCE = new LoginResponseCodec();

        private LoginResponseCodec() {
        }

        /**
         * 返回 codec 名称。
         *
         * @return 名称；不可为空；线程安全。
         */
        @Override
        public String name() {
            return "business-login-response";
        }

        /**
         * 返回消息类型。
         *
         * @return 消息类型；不可为空；线程安全。
         */
        @Override
        public Class<LoginResponseDTO> messageType() {
            return LoginResponseDTO.class;
        }

        /**
         * 写入响应。
         *
         * @param writer 写入器；不可为空。
         * @param message 消息；不可为空。
         */
        @Override
        public void write(final ZeroWriter writer, final LoginResponseDTO message) {
            int marker = writer.beginObject();
            writer.writeLong(message.uid());
            writer.writeString(message.name());
            writer.writeString(message.zone());
            writer.writeString(message.state());
            writer.endObject(marker);
        }

        /**
         * 读取响应。
         *
         * @param reader 读取器；不可为空。
         * @return 响应；不可为空；线程不安全。
         */
        @Override
        public LoginResponseDTO read(final ZeroReader reader) {
            int end = reader.beginObject();
            long uid = reader.hasRemainingInObject(end) ? reader.readLong() : 0L;
            String name = reader.hasRemainingInObject(end) ? reader.readString() : "";
            String zone = reader.hasRemainingInObject(end) ? reader.readString() : "";
            String state = reader.hasRemainingInObject(end) ? reader.readString() : "";
            reader.endObject(end);
            return new LoginResponseDTO(uid, name, zone, state);
        }
    }

    /**
     * 玩家档案 codec。
     *
     * @author zn
     */
    static final class PlayerProfileCodec implements ZeroPayloadCodec<PlayerProfile> {

        /**
         * 单例。
         */
        private static final PlayerProfileCodec INSTANCE = new PlayerProfileCodec();

        private PlayerProfileCodec() {
        }

        /**
         * 返回 codec 名称。
         *
         * @return 名称；不可为空；线程安全。
         */
        @Override
        public String name() {
            return "player-profile-full-chain";
        }

        /**
         * 返回消息类型。
         *
         * @return 消息类型；不可为空；线程安全。
         */
        @Override
        public Class<PlayerProfile> messageType() {
            return PlayerProfile.class;
        }

        /**
         * 写入档案。
         *
         * @param writer 写入器；不可为空。
         * @param message 消息；不可为空。
         */
        @Override
        public void write(final ZeroWriter writer, final PlayerProfile message) {
            int marker = writer.beginObject();
            writer.writeString(message.id());
            writer.writeLong(message.version());
            writer.writeLong(message.uid());
            writer.writeString(message.name());
            writer.writeString(message.zone());
            writer.endObject(marker);
        }

        /**
         * 读取档案。
         *
         * @param reader 读取器；不可为空。
         * @return 档案；不可为空；线程不安全。
         */
        @Override
        public PlayerProfile read(final ZeroReader reader) {
            int end = reader.beginObject();
            String id = reader.hasRemainingInObject(end) ? reader.readString() : "";
            long version = reader.hasRemainingInObject(end) ? reader.readLong() : 0L;
            long uid = reader.hasRemainingInObject(end) ? reader.readLong() : 0L;
            String name = reader.hasRemainingInObject(end) ? reader.readString() : "";
            String zone = reader.hasRemainingInObject(end) ? reader.readString() : "";
            reader.endObject(end);
            return new PlayerProfile(id, version, uid, name, zone);
        }
    }

    /**
     * 账号档案 codec。
     *
     * @author zn
     */
    static final class AccountProfileCodec implements ZeroPayloadCodec<AccountProfile> {

        /**
         * 单例。
         */
        private static final AccountProfileCodec INSTANCE = new AccountProfileCodec();

        private AccountProfileCodec() {
        }

        /**
         * 返回 codec 名称。
         *
         * @return 名称；不可为空；线程安全。
         */
        @Override
        public String name() {
            return "account-profile-full-chain";
        }

        /**
         * 返回消息类型。
         *
         * @return 消息类型；不可为空；线程安全。
         */
        @Override
        public Class<AccountProfile> messageType() {
            return AccountProfile.class;
        }

        /**
         * 写入账号档案。
         *
         * @param writer 写入器；不可为空。
         * @param message 消息；不可为空。
         */
        @Override
        public void write(final ZeroWriter writer, final AccountProfile message) {
            int marker = writer.beginObject();
            writer.writeString(message.id());
            writer.writeLong(message.version());
            writer.writeLong(message.uid());
            writer.writeString(message.channel());
            writer.endObject(marker);
        }

        /**
         * 读取账号档案。
         *
         * @param reader 读取器；不可为空。
         * @return 账号档案；不可为空；线程不安全。
         */
        @Override
        public AccountProfile read(final ZeroReader reader) {
            int end = reader.beginObject();
            String id = reader.hasRemainingInObject(end) ? reader.readString() : "";
            long version = reader.hasRemainingInObject(end) ? reader.readLong() : 0L;
            long uid = reader.hasRemainingInObject(end) ? reader.readLong() : 0L;
            String channel = reader.hasRemainingInObject(end) ? reader.readString() : "";
            reader.endObject(end);
            return new AccountProfile(id, version, uid, channel);
        }
    }

    /**
     * 字符串缓存值 codec。
     *
     * @author zn
     */
    static final class StringCacheValueCodec implements CacheValueCodec<String> {

        /**
         * 单例。
         */
        private static final StringCacheValueCodec INSTANCE = new StringCacheValueCodec();

        private StringCacheValueCodec() {
        }

        /**
         * 返回 codec 名称。
         *
         * @return 名称；不可为空；线程安全。
         */
        @Override
        public String name() {
            return "string-cache-value";
        }

        /**
         * 编码字符串。
         *
         * @param value 缓存值；不可为空。
         * @return payload 字节；不可为空；线程安全。
         */
        @Override
        public byte[] encode(final String value) {
            return value.getBytes(StandardCharsets.UTF_8);
        }

        /**
         * 解码字符串。
         *
         * @param bytes payload 字节；不可为空。
         * @return 字符串；不可为空；线程安全。
         */
        @Override
        public String decode(final byte[] bytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }
}
