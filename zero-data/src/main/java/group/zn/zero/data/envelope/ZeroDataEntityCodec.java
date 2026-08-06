package group.zn.zero.data.envelope;

import group.zn.zero.core.error.ZeroException;
import group.zn.zero.data.error.DataErrorCode;
import group.zn.zero.data.mapping.ZeroDataKeyCodec;
import group.zn.zero.data.mapping.ZeroDataKeyGenerator;
import group.zn.zero.data.mapping.ZeroDataFieldMetadata;
import group.zn.zero.data.mapping.ZeroDataObjectMetadata;
import group.zn.zero.data.mapping.NoopZeroDataKeyGenerator;
import group.zn.zero.data.model.VersionedEntity;
import group.zn.zero.protocol.buffer.ZeroReader;
import group.zn.zero.protocol.buffer.ZeroWriter;
import group.zn.zero.protocol.codec.ZeroPayloadCodec;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.time.Clock;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

/**
 * 数据实体与 zcode 信封之间的编解码桥。
 *
 * <p>该类把映射元数据、ID 编码器和生成式 payload codec 组合成统一对象存储格式。
 *
 * @param <ID> 主键类型。
 * @param <T> 实体类型。
 * @author zn
 */
public final class ZeroDataEntityCodec<ID, T extends VersionedEntity<ID>> {

    /**
     * 映射元数据。
     */
    private final ZeroDataObjectMetadata metadata;

    /**
     * payload codec。
     */
    private final ZeroPayloadCodec<T> payloadCodec;

    /**
     * 数据键 codec。
     */
    private final ZeroDataKeyCodec<ID> keyCodec;

    /**
     * 数据键生成器。
     */
    private final ZeroDataKeyGenerator<ID> keyGenerator;

    /**
     * payload codec 版本。
     */
    private final int codecVersion;

    /**
     * 时钟。
     */
    private final Clock clock;

    /**
     * 创建数据实体 zcode 编解码桥。
     *
     * @param metadata 映射元数据；不可为空。
     * @param payloadCodec payload codec；不可为空。
     * @param codecVersion payload codec 版本；必须大于 0。
     * @throws NullPointerException 当必要参数为空时抛出。
     * @throws ZeroException 当 key codec 无法创建时抛出，绑定 `DataErrorCode.MAPPING_INVALID`。
     */
    public ZeroDataEntityCodec(
            final ZeroDataObjectMetadata metadata,
            final ZeroPayloadCodec<T> payloadCodec,
            final int codecVersion) {
        this(metadata, payloadCodec, instantiateKeyCodec(metadata), codecVersion, Clock.systemUTC());
    }

    /**
     * 创建数据实体 zcode 编解码桥。
     *
     * @param metadata 映射元数据；不可为空。
     * @param payloadCodec payload codec；不可为空。
     * @param keyCodec 数据键 codec；不可为空。
     * @param codecVersion payload codec 版本；必须大于 0。
     * @param clock 时钟；不可为空。
     * @throws NullPointerException 当必要参数为空时抛出。
     * @throws IllegalArgumentException 当 codec 版本非法时抛出。
     */
    public ZeroDataEntityCodec(
            final ZeroDataObjectMetadata metadata,
            final ZeroPayloadCodec<T> payloadCodec,
            final ZeroDataKeyCodec<ID> keyCodec,
            final int codecVersion,
            final Clock clock) {
        this.metadata = Objects.requireNonNull(metadata, "metadata");
        this.payloadCodec = Objects.requireNonNull(payloadCodec, "payloadCodec");
        this.keyCodec = Objects.requireNonNull(keyCodec, "keyCodec");
        this.keyGenerator = instantiateKeyGenerator(metadata);
        if (codecVersion <= 0) {
            throw new IllegalArgumentException("codecVersion must be positive");
        }
        this.codecVersion = codecVersion;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 返回映射元数据。
     *
     * @return 映射元数据；不可为空；线程安全。
     */
    public ZeroDataObjectMetadata metadata() {
        return metadata;
    }

    /**
     * 返回 payload codec 版本。
     *
     * @return payload codec 版本；线程安全。
     */
    public int codecVersion() {
        return codecVersion;
    }

    /**
     * 编码实体 ID。
     *
     * @param id 实体 ID；不可为空。
     * @return 编码后的存储 ID；不可为空；线程安全。
     * @throws ZeroException 编码失败时抛出，必须绑定 ErrorCode。
     */
    public String encodeId(final ID id) {
        return keyCodec.encode(Objects.requireNonNull(id, "id"));
    }

    /**
     * 为保存流程准备实体身份。
     *
     * <p>普通 ID 对象必须自带 ID；配置了 ID 生成器且 ID 为空时，框架会通过
     * {@link VersionedEntity#withId(Object)} 生成带 ID 的副本。组合键对象允许从
     * `@ZeroDataKeyPart` 字段派生存储键。
     *
     * @param entity 实体；不可为空。
     * @return 带有效身份的实体；不可为空；线程安全。
     * @throws ZeroException ID 缺失且无法生成或回填时抛出，绑定 `DataErrorCode.INVALID_ENTITY`。
     */
    public T prepareForSave(final T entity) {
        T current = Objects.requireNonNull(entity, "entity");
        if (current.id() != null || metadata.compositeKey()) {
            return current;
        }
        ID generatedId = keyGenerator.generate();
        try {
            return castEntity(current.withId(generatedId));
        } catch (UnsupportedOperationException ex) {
            throw ZeroException.of(DataErrorCode.INVALID_ENTITY,
                    "entity id is null and withId is not implemented: " + metadata.objectType().getName(), ex);
        }
    }

    /**
     * 返回实体存储 ID。
     *
     * @param entity 实体；不可为空。
     * @return 存储 ID；不可为空；线程安全。
     * @throws ZeroException ID 缺失或组合键无法派生时抛出，必须绑定 ErrorCode。
     */
    public ID idOf(final T entity) {
        T current = Objects.requireNonNull(entity, "entity");
        ID id = current.id();
        if (id != null) {
            return id;
        }
        if (metadata.compositeKey()) {
            return compositeId(current);
        }
        throw ZeroException.of(DataErrorCode.INVALID_ENTITY, "entity id must not be null", null);
    }

    /**
     * 返回带指定版本号的实体副本。
     *
     * @param entity 实体；不可为空。
     * @param version 新版本号。
     * @return 带新版本号的实体；不可为空；线程安全性由实体实现保证。
     */
    public T withVersion(final T entity, final long version) {
        return castEntity(Objects.requireNonNull(entity, "entity").withVersion(version));
    }

    /**
     * 将实体编码为数据对象信封。
     *
     * @param entity 实体；不可为空。
     * @return 数据对象信封；不可为空；线程安全。
     * @throws ZeroException 编码失败时抛出，绑定 `DataErrorCode.WRITE_FAILED`。
     */
    public ZeroDataEnvelope encode(final T entity) {
        T current = Objects.requireNonNull(entity, "entity");
        try {
            if (!payloadCodec.messageType().isInstance(current)) {
                throw ZeroException.of(DataErrorCode.MAPPING_INVALID,
                        "payload codec message type mismatch: " + payloadCodec.messageType().getName(), null);
            }
            ZeroWriter writer = new ZeroWriter(payloadCodec.estimatedSize(current));
            payloadCodec.write(writer, current);
            return new ZeroDataEnvelope(
                    metadata.namespace(),
                    metadata.collection(),
                    encodeId(idOf(current)),
                    current.version(),
                    metadata.schemaVersion(),
                    codecVersion,
                    clock.millis(),
                    writer.toByteArray());
        } catch (RuntimeException ex) {
            if (ex instanceof ZeroException zeroException) {
                throw zeroException;
            }
            throw ZeroException.of(DataErrorCode.WRITE_FAILED, "encode data entity failed", ex);
        }
    }

    /**
     * 将数据对象信封解码为实体。
     *
     * @param envelope 数据对象信封；不可为空。
     * @return 实体；不可为空；线程安全。
     * @throws ZeroException 解码失败时抛出，绑定 `DataErrorCode.READ_FAILED`。
     */
    public T decode(final ZeroDataEnvelope envelope) {
        ZeroDataEnvelope current = Objects.requireNonNull(envelope, "envelope");
        validateEnvelope(current);
        try {
            ZeroReader reader = new ZeroReader(current.payload());
            T entity = payloadCodec.read(reader);
            if (reader.isReadable()) {
                throw ZeroException.of(DataErrorCode.READ_FAILED, "data payload has trailing bytes", null);
            }
            validateEntity(current, entity);
            return entity;
        } catch (RuntimeException ex) {
            if (ex instanceof ZeroException zeroException) {
                throw zeroException;
            }
            throw ZeroException.of(DataErrorCode.READ_FAILED, "decode data entity failed", ex);
        }
    }

    private void validateEnvelope(final ZeroDataEnvelope envelope) {
        if (!metadata.namespace().equals(envelope.namespace())) {
            throw ZeroException.of(DataErrorCode.READ_FAILED, "data namespace mismatch", null);
        }
        if (!metadata.collection().equals(envelope.collection())) {
            throw ZeroException.of(DataErrorCode.READ_FAILED, "data collection mismatch", null);
        }
        if (metadata.schemaVersion() != envelope.schemaVersion()) {
            throw ZeroException.of(DataErrorCode.READ_FAILED, "data schema version mismatch", null);
        }
    }

    private void validateEntity(final ZeroDataEnvelope envelope, final T entity) {
        T current = Objects.requireNonNull(entity, "entity");
        String entityId = encodeId(idOf(current));
        if (!envelope.id().equals(entityId)) {
            throw ZeroException.of(DataErrorCode.READ_FAILED, "data envelope id and payload id mismatch", null);
        }
        if (envelope.version() != current.version()) {
            throw ZeroException.of(DataErrorCode.READ_FAILED,
                    "data envelope version and payload version mismatch", null);
        }
    }

    @SuppressWarnings("unchecked")
    private static <ID> ZeroDataKeyCodec<ID> instantiateKeyCodec(final ZeroDataObjectMetadata metadata) {
        Objects.requireNonNull(metadata, "metadata");
        try {
            Constructor<? extends ZeroDataKeyCodec<?>> constructor =
                    metadata.keyCodecType().getDeclaredConstructor();
            constructor.setAccessible(true);
            return (ZeroDataKeyCodec<ID>) constructor.newInstance();
        } catch (NoSuchMethodException | InstantiationException | IllegalAccessException
                | InvocationTargetException ex) {
            throw ZeroException.of(DataErrorCode.MAPPING_INVALID,
                    "create data key codec failed: " + metadata.keyCodecType().getName(), ex);
        }
    }

    @SuppressWarnings("unchecked")
    private ZeroDataKeyGenerator<ID> instantiateKeyGenerator(final ZeroDataObjectMetadata metadata) {
        Objects.requireNonNull(metadata, "metadata");
        try {
            Constructor<? extends ZeroDataKeyGenerator<?>> constructor =
                    metadata.idGeneratorType().getDeclaredConstructor();
            constructor.setAccessible(true);
            return (ZeroDataKeyGenerator<ID>) constructor.newInstance();
        } catch (NoSuchMethodException | InstantiationException | IllegalAccessException
                | InvocationTargetException ex) {
            throw ZeroException.of(DataErrorCode.MAPPING_INVALID,
                    "create data key generator failed: " + metadata.idGeneratorType().getName(), ex);
        }
    }

    @SuppressWarnings("unchecked")
    private ID compositeId(final T entity) {
        List<ZeroDataFieldMetadata> keyParts = metadata.keyParts();
        Object[] values = new Object[keyParts.size()];
        for (int index = 0; index < keyParts.size(); index++) {
            values[index] = memberValue(entity, keyParts.get(index).javaName());
        }
        Class<?> keyType = keyType();
        if (keyType == String.class || keyType == Object.class || keyType == null) {
            return (ID) compositeString(keyParts, values);
        }
        return (ID) constructCompositeKey(keyType, values);
    }

    private Object constructCompositeKey(final Class<?> keyType, final Object[] values) {
        try {
            if (keyType.isRecord()) {
                RecordComponent[] components = keyType.getRecordComponents();
                if (components.length == values.length) {
                    Class<?>[] parameterTypes = new Class<?>[components.length];
                    for (int index = 0; index < components.length; index++) {
                        parameterTypes[index] = components[index].getType();
                    }
                    Constructor<?> constructor = keyType.getDeclaredConstructor(parameterTypes);
                    constructor.setAccessible(true);
                    return constructor.newInstance(values);
                }
            }
            for (Constructor<?> constructor : keyType.getDeclaredConstructors()) {
                if (constructor.getParameterCount() == values.length) {
                    constructor.setAccessible(true);
                    return constructor.newInstance(values);
                }
            }
            throw ZeroException.of(DataErrorCode.MAPPING_INVALID,
                    "composite key constructor not found: " + keyType.getName(), null);
        } catch (InstantiationException | IllegalAccessException | InvocationTargetException
                | NoSuchMethodException ex) {
            throw ZeroException.of(DataErrorCode.MAPPING_INVALID,
                    "create composite key failed: " + keyType.getName(), ex);
        }
    }

    private String compositeString(final List<ZeroDataFieldMetadata> keyParts, final Object[] values) {
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < keyParts.size(); index++) {
            if (index > 0) {
                builder.append('|');
            }
            builder.append(keyParts.get(index).storageName())
                    .append('=')
                    .append(Base64.getUrlEncoder().withoutPadding().encodeToString(
                            String.valueOf(Objects.requireNonNull(values[index], "keyPart"))
                                    .getBytes(StandardCharsets.UTF_8)));
        }
        return builder.toString();
    }

    private Object memberValue(final Object entity, final String javaName) {
        Class<?> objectType = entity.getClass();
        try {
            Method accessor = objectType.getDeclaredMethod(javaName);
            accessor.setAccessible(true);
            return accessor.invoke(entity);
        } catch (NoSuchMethodException ex) {
            return fieldValue(entity, javaName);
        } catch (IllegalAccessException | InvocationTargetException ex) {
            throw ZeroException.of(DataErrorCode.MAPPING_INVALID,
                    "read composite key accessor failed: " + javaName, ex);
        }
    }

    private Object fieldValue(final Object entity, final String javaName) {
        try {
            Field field = entity.getClass().getDeclaredField(javaName);
            field.setAccessible(true);
            return field.get(entity);
        } catch (NoSuchFieldException | IllegalAccessException ex) {
            throw ZeroException.of(DataErrorCode.MAPPING_INVALID,
                    "read composite key field failed: " + javaName, ex);
        }
    }

    private Class<?> keyType() {
        if (keyGenerator.getClass() != NoopZeroDataKeyGenerator.class) {
            Class<?> generatorType = genericType(keyGenerator.getClass(), ZeroDataKeyGenerator.class);
            if (generatorType != null) {
                return generatorType;
            }
        }
        return genericType(keyCodec.getClass(), ZeroDataKeyCodec.class);
    }

    private Class<?> genericType(final Class<?> implementationType, final Class<?> interfaceType) {
        for (Type type : implementationType.getGenericInterfaces()) {
            Class<?> resolved = genericType(type, interfaceType);
            if (resolved != null) {
                return resolved;
            }
        }
        Class<?> superclass = implementationType.getSuperclass();
        if (superclass == null || superclass == Object.class) {
            return null;
        }
        return genericType(superclass, interfaceType);
    }

    private Class<?> genericType(final Type type, final Class<?> interfaceType) {
        if (type instanceof ParameterizedType parameterizedType
                && parameterizedType.getRawType() == interfaceType
                && parameterizedType.getActualTypeArguments()[0] instanceof Class<?> keyType) {
            return keyType;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private T castEntity(final VersionedEntity<ID> entity) {
        return (T) entity;
    }
}
