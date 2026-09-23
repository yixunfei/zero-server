package group.zn.zero.codegen.scaffold;

import group.zn.zero.runtime.capability.MavenCoordinate;
import group.zn.zero.runtime.capability.RuntimeCapabilityModel;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** 七类本地项目模板的单一不可变定义。 */
public record ScaffoldTemplate(
        String id,
        String directory,
        String protocolTemplate,
        String protocolOutput,
        String description,
        List<String> keywords,
        String useCase,
        String summaryPrefix,
        String productionGap,
        List<String> capabilityIds,
        List<ScaffoldDependency> directDependencies) {

    public ScaffoldTemplate {
        id = requireText(id, "id");
        directory = requireText(directory, "directory");
        protocolTemplate = Objects.requireNonNull(protocolTemplate, "protocolTemplate").trim();
        protocolOutput = Objects.requireNonNull(protocolOutput, "protocolOutput").trim();
        if (protocolTemplate.isEmpty() != protocolOutput.isEmpty()) {
            throw new IllegalArgumentException("protocol template and output must both be present or absent");
        }
        description = requireText(description, "description");
        keywords = copyText(keywords, "keyword");
        useCase = requireText(useCase, "useCase");
        summaryPrefix = requireText(summaryPrefix, "summaryPrefix");
        productionGap = requireText(productionGap, "productionGap");
        capabilityIds = copyText(capabilityIds, "capabilityId").stream().distinct().sorted().toList();
        directDependencies = Objects.requireNonNull(directDependencies, "directDependencies").stream()
                .map(dependency -> Objects.requireNonNull(dependency, "dependency"))
                .distinct()
                .sorted()
                .toList();
        if (!id.matches("[a-z][a-z0-9]*(?:-[a-z0-9]+)*")) {
            throw new IllegalArgumentException("template id must be a stable lowercase identifier");
        }
    }

    public int recommendationScore(final String query) {
        String normalized = normalize(requireText(query, "query"));
        int score = normalized.contains(normalize(id)) ? 100 : 0;
        if (normalized.contains(normalize(description))) {
            score += 20;
        }
        for (String keyword : keywords) {
            if (normalized.contains(normalize(keyword))) {
                score += 10;
            }
        }
        return score;
    }

    public boolean generatesProtocol() {
        return !protocolTemplate.isEmpty();
    }

    public List<MavenCoordinate> frameworkArtifacts(final RuntimeCapabilityModel model) {
        return Objects.requireNonNull(model, "model").artifactsFor(capabilityIds);
    }

    private static List<String> copyText(final List<String> values, final String label) {
        return Objects.requireNonNull(values, label).stream()
                .map(value -> requireText(value, label))
                .toList();
    }

    private static String requireText(final String value, final String label) {
        String checked = Objects.requireNonNull(value, label).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }

    private static String normalize(final String value) {
        return value.toLowerCase(Locale.ROOT).replace('_', '-').replace(' ', '-');
    }
}
