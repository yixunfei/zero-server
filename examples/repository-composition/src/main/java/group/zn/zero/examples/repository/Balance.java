package group.zn.zero.examples.repository;

import group.zn.zero.data.mapping.ZeroDataField;
import group.zn.zero.data.mapping.ZeroDataId;
import group.zn.zero.data.mapping.ZeroDataObject;
import group.zn.zero.data.mapping.ZeroDataVersion;
import group.zn.zero.data.model.VersionedEntity;

@ZeroDataObject(namespace = "composition_example", collection = "balances", schemaVersion = 1)
public record Balance(@ZeroDataId String id, @ZeroDataVersion long version,
                      @ZeroDataField(order = 1) long amount) implements VersionedEntity<String> {
    @Override
    public Balance withVersion(final long next) {
        return new Balance(id, next, amount);
    }
}
