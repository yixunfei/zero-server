package group.zn.zero.codegen.scaffold;

import javax.lang.model.SourceVersion;

/** Validates Java names derived from scaffold inputs before any files are written. */
final class ScaffoldJavaNames {
    private ScaffoldJavaNames() { }

    static String applicationClass(final String projectName) {
        StringBuilder name = new StringBuilder();
        boolean upperNext = true;
        for (int index = 0; index < projectName.length(); index++) {
            char current = projectName.charAt(index);
            if (!Character.isLetterOrDigit(current)) {
                upperNext = true;
            } else {
                name.append(upperNext ? Character.toUpperCase(current) : current);
                upperNext = false;
            }
        }
        if (name.isEmpty()) {
            throw new IllegalArgumentException("projectName must contain at least one letter or digit");
        }
        String result = name + "Application";
        if (!SourceVersion.isIdentifier(result) || SourceVersion.isKeyword(result, SourceVersion.RELEASE_21)) {
            throw new IllegalArgumentException("projectName must produce a valid Java application class name");
        }
        return result;
    }

    static void validatePackage(final String packageName) {
        if (!SourceVersion.isName(packageName, SourceVersion.RELEASE_21)) {
            throw new IllegalArgumentException("packageName must be a valid Java package without keywords");
        }
    }
}
