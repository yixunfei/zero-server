package group.zn.zero.runtime.config;

import group.zn.zero.runtime.api.ComponentId;
import group.zn.zero.runtime.internal.RuntimeIdentifiers;
import java.time.Duration;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * owner 明确、可校验且可脱敏的 typed 配置 key。
 *
 * @param <T> 配置值类型。
 * @author zn
 */
public final class ConfigKey<T> {

    /** key owner。 */
    private final ComponentId owner;

    /** 稳定逻辑名称。 */
    private final String logicalName;

    /** 类型令牌。 */
    private final Class<T> type;

    /** 解码器。 */
    private final ConfigDecoder<T> decoder;

    /** validator。 */
    private final Predicate<T> validator;

    /** validator 稳定代码。 */
    private final String validationCode;

    /** 是否必填。 */
    private final boolean required;

    /** 默认值。 */
    private final T defaultValue;

    /** 是否敏感。 */
    private final boolean sensitive;

    /** reload 语义。 */
    private final ConfigReloadability reloadability;

    /** 允许来源。 */
    private final Set<ConfigSourceKind> acceptedSources;

    /** 每类来源的 allowlist alias。 */
    private final Map<ConfigSourceKind, String> aliases;

    private ConfigKey(final Builder<T> builder) {
        owner = builder.owner;
        logicalName = builder.logicalName;
        type = builder.type;
        decoder = builder.decoder;
        validator = builder.validator;
        validationCode = builder.validationCode;
        required = builder.required;
        defaultValue = builder.defaultValue;
        sensitive = builder.sensitive;
        reloadability = builder.reloadability;
        acceptedSources = Set.copyOf(builder.acceptedSources);
        aliases = Map.copyOf(builder.aliases);
    }

    /**
     * 创建字符串 key builder。
     *
     * @param owner owner；不可为空。
     * @param logicalName 稳定逻辑名称；不可为空。
     * @return builder；不可为空。
     */
    public static Builder<String> string(final ComponentId owner, final String logicalName) {
        return builder(owner, logicalName, String.class, raw -> raw);
    }

    /**
     * 创建整数 key builder。
     *
     * @param owner owner；不可为空。
     * @param logicalName 稳定逻辑名称；不可为空。
     * @return builder；不可为空。
     */
    public static Builder<Integer> integer(final ComponentId owner, final String logicalName) {
        return builder(owner, logicalName, Integer.class, Integer::valueOf);
    }

    /**
     * 创建 ISO-8601 Duration key builder。
     *
     * @param owner owner；不可为空。
     * @param logicalName 稳定逻辑名称；不可为空。
     * @return builder；不可为空。
     */
    public static Builder<Duration> duration(final ComponentId owner, final String logicalName) {
        return builder(owner, logicalName, Duration.class, Duration::parse);
    }

    /**
     * 创建自定义 typed key builder。
     *
     * @param owner owner；不可为空。
     * @param logicalName 稳定逻辑名称；不可为空。
     * @param type 类型令牌；不可为空。
     * @param decoder 解码器；不可为空。
     * @param <T> 配置类型。
     * @return builder；不可为空。
     */
    public static <T> Builder<T> builder(
            final ComponentId owner,
            final String logicalName,
            final Class<T> type,
            final ConfigDecoder<T> decoder) {
        return new Builder<>(owner, logicalName, type, decoder);
    }

    public ComponentId owner() {
        return owner;
    }

    public String logicalName() {
        return logicalName;
    }

    public Class<T> type() {
        return type;
    }

    public boolean required() {
        return required;
    }

    public Optional<T> defaultValue() {
        return Optional.ofNullable(defaultValue);
    }

    public boolean sensitive() {
        return sensitive;
    }

    public ConfigReloadability reloadability() {
        return reloadability;
    }

    public Set<ConfigSourceKind> acceptedSources() {
        return acceptedSources;
    }

    public String aliasFor(final ConfigSourceKind sourceKind) {
        return aliases.getOrDefault(Objects.requireNonNull(sourceKind, "sourceKind"), logicalName);
    }

    T decode(final String raw) throws Exception {
        T decoded = decoder.decode(Objects.requireNonNull(raw, "raw"));
        return Objects.requireNonNull(decoded, "decoded");
    }

    boolean valid(final T value) {
        return validator.test(Objects.requireNonNull(value, "value"));
    }

    String validationCode() {
        return validationCode;
    }

    @Override
    public boolean equals(final Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ConfigKey<?> that)) {
            return false;
        }
        return owner.equals(that.owner)
                && logicalName.equals(that.logicalName)
                && type.equals(that.type);
    }

    @Override
    public int hashCode() {
        return Objects.hash(owner, logicalName, type);
    }

    @Override
    public String toString() {
        return logicalName;
    }

    /**
     * ConfigKey 的可变构建器；构建后输入会冻结。
     *
     * @param <T> 配置类型。
     * @author zn
     */
    public static final class Builder<T> {

        private final ComponentId owner;
        private final String logicalName;
        private final Class<T> type;
        private final ConfigDecoder<T> decoder;
        private Predicate<T> validator = value -> true;
        private String validationCode = "valid";
        private boolean required = true;
        private T defaultValue;
        private boolean sensitive;
        private ConfigReloadability reloadability = ConfigReloadability.STARTUP_ONLY;
        private Set<ConfigSourceKind> acceptedSources = EnumSet.of(
                ConfigSourceKind.PROGRAMMATIC,
                ConfigSourceKind.SYSTEM_PROPERTY,
                ConfigSourceKind.ENVIRONMENT,
                ConfigSourceKind.FILE,
                ConfigSourceKind.REMOTE);
        private final Map<ConfigSourceKind, String> aliases = new EnumMap<>(ConfigSourceKind.class);

        private Builder(
                final ComponentId owner,
                final String logicalName,
                final Class<T> type,
                final ConfigDecoder<T> decoder) {
            this.owner = Objects.requireNonNull(owner, "owner");
            this.logicalName = RuntimeIdentifiers.requireStableId(logicalName, "logicalName");
            this.type = Objects.requireNonNull(type, "type");
            this.decoder = Objects.requireNonNull(decoder, "decoder");
        }

        public Builder<T> optional() {
            required = false;
            defaultValue = null;
            return this;
        }

        public Builder<T> defaultValue(final T value) {
            defaultValue = Objects.requireNonNull(value, "value");
            required = false;
            return this;
        }

        public Builder<T> validate(final Predicate<T> predicate, final String code) {
            validator = Objects.requireNonNull(predicate, "predicate");
            validationCode = RuntimeIdentifiers.requireSafeAlias(code, "validationCode");
            return this;
        }

        public Builder<T> sensitive() {
            sensitive = true;
            return this;
        }

        public Builder<T> reloadable() {
            reloadability = ConfigReloadability.RELOADABLE;
            return this;
        }

        public Builder<T> acceptedSources(final Set<ConfigSourceKind> sourceKinds) {
            Set<ConfigSourceKind> requested = Objects.requireNonNull(sourceKinds, "sourceKinds");
            if (requested.isEmpty()) {
                throw new IllegalArgumentException("acceptedSources must not be empty");
            }
            EnumSet<ConfigSourceKind> checked = EnumSet.copyOf(requested);
            checked.remove(ConfigSourceKind.DEFAULT);
            if (checked.isEmpty()) {
                throw new IllegalArgumentException("acceptedSources must not be empty");
            }
            acceptedSources = checked;
            return this;
        }

        public Builder<T> alias(final ConfigSourceKind sourceKind, final String alias) {
            ConfigSourceKind checkedKind = Objects.requireNonNull(sourceKind, "sourceKind");
            if (checkedKind == ConfigSourceKind.DEFAULT) {
                throw new IllegalArgumentException("DEFAULT does not have a source alias");
            }
            aliases.put(checkedKind, RuntimeIdentifiers.requireSafeAlias(alias, "sourceAlias"));
            return this;
        }

        public ConfigKey<T> build() {
            return new ConfigKey<>(this);
        }
    }
}
