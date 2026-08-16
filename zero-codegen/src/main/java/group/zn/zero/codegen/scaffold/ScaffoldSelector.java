package group.zn.zero.codegen.scaffold;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** 基于业务关键词的确定性模板选择器。 */
public final class ScaffoldSelector {

    private final ScaffoldCatalog catalog;

    public ScaffoldSelector(final ScaffoldCatalog catalog) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
    }

    public ScaffoldTemplate select(final String query) {
        return recommendations(query).stream()
                .findFirst()
                .map(Recommendation::template)
                .orElseGet(() -> catalog.require("local"));
    }

    public List<Recommendation> recommendations(final String query) {
        if (Objects.requireNonNull(query, "query").isBlank()) {
            throw new IllegalArgumentException("recommend keywords must not be blank");
        }
        return catalog.templates().stream()
                .map(template -> new Recommendation(template, template.recommendationScore(query)))
                .filter(recommendation -> recommendation.score() > 0)
                .sorted(Comparator.comparingInt(Recommendation::score)
                        .reversed()
                        .thenComparing(recommendation -> recommendation.template().id()))
                .toList();
    }

    /** @param template 模板。 @param score 匹配分数。 */
    public record Recommendation(ScaffoldTemplate template, int score) {
        public Recommendation {
            Objects.requireNonNull(template, "template");
            if (score < 0) {
                throw new IllegalArgumentException("recommendation score must not be negative");
            }
        }
    }
}
