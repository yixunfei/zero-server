package group.zn.zero.data.repository;

import group.zn.zero.data.mapping.ZeroDataMappingIntrospector;
import group.zn.zero.data.mapping.ZeroDataObjectMetadata;
import group.zn.zero.data.model.VersionedEntity;
import group.zn.zero.protocol.codec.ZeroPayloadCodec;
import java.lang.invoke.MethodType;
import java.util.Objects;

/** Typed repository identity and the existing envelope mapping/codec contract. */
public record RepositoryDefinition<ID, T extends VersionedEntity<ID>>(
        String name, Class<ID> idType, Class<T> entityType,
        ZeroDataObjectMetadata metadata, ZeroPayloadCodec<T> codec, int codecVersion) {
    public RepositoryDefinition {
        name = RepositorySource.identifier(name);
        Objects.requireNonNull(idType, "idType");
        Objects.requireNonNull(entityType, "entityType");
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(codec, "codec");
        if (codecVersion <= 0 || !entityType.equals(metadata.objectType()) || !entityType.equals(codec.messageType())) {
            throw new IllegalArgumentException("repository entity mapping or codec version is invalid");
        }
        metadata.idField().ifPresent(field -> {
            Class<?> mapped = MethodType.methodType(field.fieldType()).wrap().returnType();
            if (!mapped.equals(idType)) {
                throw new IllegalArgumentException("repository ID type does not match entity mapping");
            }
        });
    }

    public static <ID, T extends VersionedEntity<ID>> RepositoryDefinition<ID, T> of(
            final String name, final Class<ID> idType, final Class<T> entityType,
            final ZeroPayloadCodec<T> codec, final int codecVersion) {
        return new RepositoryDefinition<>(name, idType, entityType,
                new ZeroDataMappingIntrospector().inspect(entityType), codec, codecVersion);
    }
}
