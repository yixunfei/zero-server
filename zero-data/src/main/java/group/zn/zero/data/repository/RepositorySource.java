package group.zn.zero.data.repository;

import java.util.Objects;

/** A named storage implementation; names are chosen at the composition root. */
public record RepositorySource(String name, RepositoryFactory factory) {
    public RepositorySource {
        name = identifier(name);
        Objects.requireNonNull(factory, "factory");
    }

    static String identifier(final String value) {
        String checked = Objects.requireNonNull(value, "identifier");
        if (!checked.matches("[A-Za-z][A-Za-z0-9_.-]{0,127}")) {
            throw new IllegalArgumentException("repository identifier must be a bounded stable name");
        }
        return checked;
    }

    @Override
    public String toString() {
        return "RepositorySource[name=" + name + "]";
    }
}
