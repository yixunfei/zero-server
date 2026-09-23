package group.zn.zero.data.repository;

import group.zn.zero.data.model.VersionedEntity;
import java.util.Objects;

/** The logical storage role belongs to the application, independently of its database choice. */
public record RepositoryRequest<ID, T extends VersionedEntity<ID>>(
        String role, RepositoryDefinition<ID, T> definition) {
    public RepositoryRequest {
        role = RepositorySource.identifier(role);
        Objects.requireNonNull(definition, "definition");
    }
}
