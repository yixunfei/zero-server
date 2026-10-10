package group.zn.zero.data.envelope;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.HashMap;
import java.util.Map;

/** 只解析键 SPI 的单个具体类型参数；沿父类及接口传递绑定，无共享可变状态。 @author zn */
final class KeyTypeResolver {
    private KeyTypeResolver() { }

    static Class<?> resolve(final Class<?> implementation, final Class<?> contract) {
        return resolve(implementation, contract, Map.of());
    }

    private static Class<?> resolve(final Type type, final Class<?> contract,
            final Map<TypeVariable<?>, Type> inherited) {
        Class<?> raw;
        Map<TypeVariable<?>, Type> bindings = new HashMap<>(inherited);
        if (type instanceof ParameterizedType parameterized && parameterized.getRawType() instanceof Class<?> clazz) {
            raw = clazz;
            TypeVariable<?>[] variables = raw.getTypeParameters();
            Type[] arguments = parameterized.getActualTypeArguments();
            for (int i = 0; i < variables.length; i++) bindings.put(variables[i], substitute(arguments[i], inherited));
        } else if (type instanceof Class<?> clazz) {
            raw = clazz;
        } else return null;
        if (raw == contract) {
            Type key = substitute(raw.getTypeParameters()[0], bindings);
            return key instanceof Class<?> concrete ? concrete : null;
        }
        for (Type parent : raw.getGenericInterfaces()) {
            Class<?> resolved = resolve(parent, contract, bindings);
            if (resolved != null) return resolved;
        }
        return raw.getGenericSuperclass() == null ? null : resolve(raw.getGenericSuperclass(), contract, bindings);
    }

    private static Type substitute(final Type type, final Map<TypeVariable<?>, Type> bindings) {
        Type current = type;
        // 绑定来自有限继承链，最多经过绑定表的大小，避免原始/自引用泛型产生循环。
        for (int i = 0; i < bindings.size() && current instanceof TypeVariable<?> variable; i++) {
            Type next = bindings.get(variable);
            if (next == null || next == current) break;
            current = next;
        }
        return current;
    }
}
