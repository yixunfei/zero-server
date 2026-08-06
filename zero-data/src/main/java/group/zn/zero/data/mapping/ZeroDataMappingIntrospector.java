package group.zn.zero.data.mapping;

import group.zn.zero.core.error.ZeroException;
import group.zn.zero.data.error.DataErrorCode;
import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 数据映射元数据解析器。
 *
 * <p>该解析器只在启动和仓库注册阶段使用，运行期 Repository 不应反复解析反射元数据。
 *
 * @author zn
 */
public final class ZeroDataMappingIntrospector {

    /**
     * 解析数据对象映射。
     *
     * @param objectType 数据对象类型；不可为空。
     * @return 数据对象元数据；不可为空；线程安全。
     * @throws NullPointerException 当对象类型为空时抛出。
     * @throws ZeroException 映射非法时抛出，绑定 `DataErrorCode.MAPPING_INVALID`。
     */
    public ZeroDataObjectMetadata inspect(final Class<?> objectType) {
        Class<?> current = Objects.requireNonNull(objectType, "objectType");
        ZeroDataObject dataObject = current.getAnnotation(ZeroDataObject.class);
        if (dataObject == null) {
            throw invalid("missing @ZeroDataObject: " + current.getName());
        }
        validateObject(dataObject, current);

        ZeroDataCompositeKey compositeKey = current.getAnnotation(ZeroDataCompositeKey.class);
        List<ZeroDataFieldMetadata> fields = new ArrayList<>();
        List<ZeroDataFieldMetadata> keyParts = new ArrayList<>();
        ZeroDataFieldMetadata idField = null;
        ZeroDataFieldMetadata versionField = null;
        Class<? extends ZeroDataKeyGenerator<?>> idGeneratorType = NoopZeroDataKeyGenerator.class;

        for (MemberView member : discoverMembers(current)) {
            if (member.hasAnnotation(ZeroDataIgnore.class)) {
                continue;
            }
            validateNestedDataObject(member);
            if (member.hasAnnotation(ZeroDataId.class) || idField == null && "id".equals(member.name())) {
                idField = unique(idField, metadata(member, ZeroDataFieldKind.ID, -1000, member.name(), false),
                        "duplicate id field");
                ZeroDataId idAnnotation = member.annotation(ZeroDataId.class);
                if (idAnnotation != null) {
                    idGeneratorType = idAnnotation.generator();
                }
                fields.add(idField);
                continue;
            }
            if (member.hasAnnotation(ZeroDataVersion.class)
                    || versionField == null && "version".equals(member.name())) {
                versionField = unique(versionField,
                        metadata(member, ZeroDataFieldKind.VERSION, -999, member.name(), false),
                        "duplicate version field");
                fields.add(versionField);
                continue;
            }
            ZeroDataKeyPart keyPart = member.annotation(ZeroDataKeyPart.class);
            if (keyPart != null) {
                ZeroDataFieldMetadata metadata = metadata(member, ZeroDataFieldKind.KEY_PART,
                        keyPart.order(), storageName(keyPart.name(), member.name()), false);
                keyParts.add(metadata);
                fields.add(metadata);
                continue;
            }
            memberRelationship(member).ifPresent(fields::add);
            ZeroDataField field = member.annotation(ZeroDataField.class);
            if (field != null && memberRelationship(member).isEmpty()) {
                fields.add(metadata(member, ZeroDataFieldKind.DATA, field.order(),
                        storageName(field.name(), member.name()), field.nullable()));
            }
        }

        validateKey(current, compositeKey, keyParts, idField);
        validateVersion(current, versionField);
        validateDataFieldOrders(fields);
        return new ZeroDataObjectMetadata(
                current,
                dataObject.namespace(),
                dataObject.collection(),
                dataObject.schemaVersion(),
                dataObject.keyPrefix(),
                compositeKey != null,
                compositeKey == null ? DefaultZeroDataKeyCodec.class : compositeKey.codec(),
                idGeneratorType,
                fields,
                keyParts,
                Optional.ofNullable(idField),
                Optional.ofNullable(versionField));
    }

    private void validateObject(final ZeroDataObject dataObject, final Class<?> objectType) {
        if (dataObject.namespace().isBlank()) {
            throw invalid("data namespace must not be blank: " + objectType.getName());
        }
        if (dataObject.collection().isBlank()) {
            throw invalid("data collection must not be blank: " + objectType.getName());
        }
        if (dataObject.schemaVersion() <= 0) {
            throw invalid("schema version must be positive: " + objectType.getName());
        }
    }

    private void validateKey(
            final Class<?> objectType,
            final ZeroDataCompositeKey compositeKey,
            final List<ZeroDataFieldMetadata> keyParts,
            final ZeroDataFieldMetadata idField) {
        if (compositeKey != null && keyParts.isEmpty()) {
            throw invalid("composite key requires @ZeroDataKeyPart: " + objectType.getName());
        }
        if (compositeKey == null && idField == null) {
            throw invalid("data object requires id field or @ZeroDataCompositeKey: " + objectType.getName());
        }
        validateKeyPartOrders(keyParts);
    }

    private void validateVersion(final Class<?> objectType, final ZeroDataFieldMetadata versionField) {
        if (versionField == null) {
            throw invalid("data object requires version field: " + objectType.getName());
        }
    }

    private void validateDataFieldOrders(final List<ZeroDataFieldMetadata> fields) {
        Set<Integer> orders = new HashSet<>();
        for (ZeroDataFieldMetadata field : fields) {
            if (field.kind() == ZeroDataFieldKind.ID
                    || field.kind() == ZeroDataFieldKind.VERSION
                    || field.kind() == ZeroDataFieldKind.KEY_PART) {
                continue;
            }
            if (!orders.add(field.order())) {
                throw invalid("duplicate data field order: " + field.order());
            }
        }
    }

    private void validateKeyPartOrders(final List<ZeroDataFieldMetadata> keyParts) {
        Set<Integer> orders = new HashSet<>();
        for (ZeroDataFieldMetadata field : keyParts) {
            if (!orders.add(field.order())) {
                throw invalid("duplicate key part order: " + field.order());
            }
        }
    }

    private ZeroDataFieldMetadata unique(
            final ZeroDataFieldMetadata previous,
            final ZeroDataFieldMetadata current,
            final String message) {
        if (previous != null) {
            throw invalid(message);
        }
        return current;
    }

    private Optional<ZeroDataFieldMetadata> memberRelationship(final MemberView member) {
        ZeroDataReference reference = member.annotation(ZeroDataReference.class);
        ZeroDataReferenceList referenceList = member.annotation(ZeroDataReferenceList.class);
        ZeroDataOwnedCollection ownedCollection = member.annotation(ZeroDataOwnedCollection.class);
        ZeroDataEmbedded embedded = member.annotation(ZeroDataEmbedded.class);
        int count = (reference == null ? 0 : 1)
                + (referenceList == null ? 0 : 1)
                + (ownedCollection == null ? 0 : 1)
                + (embedded == null ? 0 : 1);
        if (count > 1) {
            throw invalid("data field declares multiple relationship modes: " + member.name());
        }
        ZeroDataField field = member.annotation(ZeroDataField.class);
        int order = field == null ? 0 : field.order();
        String storageName = field == null ? member.name() : storageName(field.name(), member.name());
        boolean nullable = field != null && field.nullable();
        if (reference != null) {
            return Optional.of(relation(member, ZeroDataFieldKind.REFERENCE, order, storageName,
                    reference.target(), reference.collection(), nullable));
        }
        if (referenceList != null) {
            return Optional.of(relation(member, ZeroDataFieldKind.REFERENCE_LIST, order, storageName,
                    referenceList.target(), referenceList.collection(), nullable));
        }
        if (ownedCollection != null) {
            return Optional.of(relation(member, ZeroDataFieldKind.OWNED_COLLECTION, order, storageName,
                    ownedCollection.target(), ownedCollection.collection(), nullable));
        }
        if (embedded != null) {
            return Optional.of(metadata(member, ZeroDataFieldKind.EMBEDDED, order, storageName, nullable));
        }
        return Optional.empty();
    }

    private ZeroDataFieldMetadata relation(
            final MemberView member,
            final ZeroDataFieldKind kind,
            final int order,
            final String storageName,
            final Class<?> target,
            final String collection,
            final boolean nullable) {
        String currentCollection = collection.isBlank() ? collectionOf(target) : collection;
        return new ZeroDataFieldMetadata(
                member.name(),
                storageName,
                order,
                member.type(),
                kind,
                Optional.of(target),
                Optional.of(currentCollection),
                nullable);
    }

    private ZeroDataFieldMetadata metadata(
            final MemberView member,
            final ZeroDataFieldKind kind,
            final int order,
            final String storageName,
            final boolean nullable) {
        return new ZeroDataFieldMetadata(
                member.name(),
                storageName,
                order,
                member.type(),
                kind,
                Optional.empty(),
                Optional.empty(),
                nullable);
    }

    private void validateNestedDataObject(final MemberView member) {
        boolean explicit = member.hasAnnotation(ZeroDataEmbedded.class)
                || member.hasAnnotation(ZeroDataReference.class)
                || member.hasAnnotation(ZeroDataReferenceList.class)
                || member.hasAnnotation(ZeroDataOwnedCollection.class);
        Optional<Class<?>> nestedType = nestedDataObjectType(member);
        if (nestedType.isPresent() && !explicit) {
            throw invalid("nested data object requires explicit relationship annotation: "
                    + member.name() + " -> " + nestedType.orElseThrow().getName());
        }
    }

    private Optional<Class<?>> nestedDataObjectType(final MemberView member) {
        if (member.type().isAnnotationPresent(ZeroDataObject.class)) {
            return Optional.of(member.type());
        }
        Optional<Class<?>> elementType = collectionElementType(member.genericType());
        if (elementType.isPresent() && elementType.orElseThrow().isAnnotationPresent(ZeroDataObject.class)) {
            return elementType;
        }
        return Optional.empty();
    }

    private Optional<Class<?>> collectionElementType(final Type type) {
        if (!(type instanceof ParameterizedType parameterizedType)) {
            return Optional.empty();
        }
        Type rawType = parameterizedType.getRawType();
        if (!(rawType instanceof Class<?> rawClass) || !Iterable.class.isAssignableFrom(rawClass)) {
            return Optional.empty();
        }
        Type actualType = parameterizedType.getActualTypeArguments()[0];
        return actualType instanceof Class<?> actualClass ? Optional.of(actualClass) : Optional.empty();
    }

    private String collectionOf(final Class<?> target) {
        ZeroDataObject object = target.getAnnotation(ZeroDataObject.class);
        if (object == null || object.collection().isBlank()) {
            throw invalid("reference target missing @ZeroDataObject collection: " + target.getName());
        }
        return object.collection();
    }

    private String storageName(final String annotationName, final String fallback) {
        return annotationName == null || annotationName.isBlank() ? fallback : annotationName;
    }

    private List<MemberView> discoverMembers(final Class<?> objectType) {
        if (objectType.isRecord()) {
            List<MemberView> members = new ArrayList<>();
            for (RecordComponent component : objectType.getRecordComponents()) {
                members.add(new MemberView(
                        component.getName(),
                        component.getType(),
                        component.getGenericType(),
                        component,
                        declaredField(objectType, component.getName()).orElse(null),
                        component.getAccessor()));
            }
            return members;
        }
        List<MemberView> members = new ArrayList<>();
        for (Field field : objectType.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())) {
                members.add(new MemberView(field.getName(), field.getType(), field.getGenericType(), field, field, null));
            }
        }
        members.sort(Comparator.comparing(MemberView::name));
        return members;
    }

    private Optional<Field> declaredField(final Class<?> objectType, final String name) {
        try {
            return Optional.of(objectType.getDeclaredField(name));
        } catch (NoSuchFieldException ex) {
            return Optional.empty();
        }
    }

    private ZeroException invalid(final String message) {
        return ZeroException.of(DataErrorCode.MAPPING_INVALID, message, null);
    }

    /**
     * Java 成员视图。
     *
     * @param name 成员名。
     * @param type 成员类型。
     * @param genericType 泛型类型。
     * @param primary 主注解来源。
     * @param field 字段注解来源。
     * @param accessor 访问器注解来源。
     */
    private record MemberView(
            String name,
            Class<?> type,
            Type genericType,
            AnnotatedElement primary,
            Field field,
            Method accessor) {

        /**
         * 判断成员是否存在指定注解。
         *
         * @param annotationType 注解类型；不可为空。
         * @return true 表示存在；线程安全。
         */
        boolean hasAnnotation(final Class<? extends Annotation> annotationType) {
            return annotation(annotationType) != null;
        }

        /**
         * 返回指定注解。
         *
         * @param annotationType 注解类型；不可为空。
         * @param <A> 注解类型。
         * @return 注解；不存在时为空；线程安全。
         */
        <A extends Annotation> A annotation(final Class<A> annotationType) {
            A annotation = primary == null ? null : primary.getAnnotation(annotationType);
            if (annotation == null && field != null) {
                annotation = field.getAnnotation(annotationType);
            }
            if (annotation == null && accessor != null) {
                annotation = accessor.getAnnotation(annotationType);
            }
            return annotation;
        }
    }
}
