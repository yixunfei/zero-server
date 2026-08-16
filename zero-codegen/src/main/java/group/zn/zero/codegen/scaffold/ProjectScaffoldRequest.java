package group.zn.zero.codegen.scaffold;

import java.nio.file.Path;
import java.util.Objects;

/** 生成一个本地游戏项目所需的显式输入。 */
public record ProjectScaffoldRequest(
        String projectName,
        String packageName,
        Path outputDirectory,
        String zeroVersion,
        ScaffoldTemplate template,
        Path templateRoot,
        String selectedFromKeywords,
        boolean force) {

    public ProjectScaffoldRequest {
        projectName = requireText(projectName, "projectName");
        packageName = requireText(packageName, "packageName");
        outputDirectory = Objects.requireNonNull(outputDirectory, "outputDirectory").toAbsolutePath().normalize();
        zeroVersion = requireText(zeroVersion, "zeroVersion");
        template = Objects.requireNonNull(template, "template");
        templateRoot = Objects.requireNonNull(templateRoot, "templateRoot").toAbsolutePath().normalize();
        selectedFromKeywords = Objects.requireNonNull(selectedFromKeywords, "selectedFromKeywords").trim();
        if (!projectName.matches("[A-Za-z0-9_.-]+")) {
            throw new IllegalArgumentException("projectName contains unsupported characters");
        }
        if (!packageName.matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+")) {
            throw new IllegalArgumentException("packageName must be a valid dotted Java package");
        }
    }

    private static String requireText(final String value, final String label) {
        String checked = Objects.requireNonNull(value, label).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }
}
