package group.zn.zero.data.repository;

import group.zn.zero.core.error.ZeroException;
import group.zn.zero.data.error.DataErrorCode;
import group.zn.zero.data.model.VersionedEntity;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Immutable, explicit application-role to storage-source routing. No implicit first/default source. */
public final class RepositoryCatalog {
    private final Map<String, RepositoryFactory> roles;

    public RepositoryCatalog(final Collection<RepositorySource> sources, final Map<String, String> bindings) {
        var named = new LinkedHashMap<String, RepositoryFactory>();
        for (RepositorySource source : sources) {
            if (named.putIfAbsent(source.name(), source.factory()) != null) {
                throw new IllegalArgumentException("duplicate repository source: " + source.name());
            }
        }
        var resolved = new LinkedHashMap<String, RepositoryFactory>();
        bindings.forEach((role, source) -> {
            String checkedRole = RepositorySource.identifier(role);
            RepositorySource.identifier(source);
            RepositoryFactory factory = named.get(source);
            if (factory == null) {
                throw ZeroException.of(DataErrorCode.REPOSITORY_NOT_FOUND,
                        "repository source missing for role: " + checkedRole, null);
            }
            resolved.put(checkedRole, factory);
        });
        roles = Map.copyOf(resolved);
    }

    public <ID, T extends VersionedEntity<ID>> CrudRepository<ID, T> create(
            final RepositoryRequest<ID, T> request) {
        Objects.requireNonNull(request, "request");
        RepositoryFactory factory = roles.get(request.role());
        if (factory == null) {
            throw ZeroException.of(DataErrorCode.REPOSITORY_NOT_FOUND,
                    "repository role is not bound: " + request.role(), null);
        }
        return factory.create(request.definition());
    }
}
