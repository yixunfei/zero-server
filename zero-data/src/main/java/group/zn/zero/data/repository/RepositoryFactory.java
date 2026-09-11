package group.zn.zero.data.repository;

import group.zn.zero.data.model.VersionedEntity;

/** Creates or reuses typed repositories. The supplying adapter retains connection ownership. */
public interface RepositoryFactory {
    <ID, T extends VersionedEntity<ID>> CrudRepository<ID, T> create(RepositoryDefinition<ID, T> definition);
}
