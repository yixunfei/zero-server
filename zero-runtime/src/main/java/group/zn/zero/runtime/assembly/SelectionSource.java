package group.zn.zero.runtime.assembly;

import group.zn.zero.runtime.internal.RuntimeIdentifiers;
import java.util.Objects;

/**
 * 可审计且不包含配置值的选择来源。
 *
 * @param kind 来源类别。
 * @param id 稳定来源 ID。
 * @author zn
 */
public record SelectionSource(SelectionSourceKind kind, String id) {

    public SelectionSource {
        kind = Objects.requireNonNull(kind, "kind");
        id = RuntimeIdentifiers.requireStableId(id, "selectionSourceId");
    }

    public static SelectionSource programmatic(final String id) {
        return new SelectionSource(SelectionSourceKind.PROGRAMMATIC, id);
    }

    public static SelectionSource deployment(final String id) {
        return new SelectionSource(SelectionSourceKind.DEPLOYMENT, id);
    }
}
