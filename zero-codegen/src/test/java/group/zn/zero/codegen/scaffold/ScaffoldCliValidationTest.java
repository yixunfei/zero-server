package group.zn.zero.codegen.scaffold;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScaffoldCliValidationTest {
    @TempDir
    private Path temporary;

    @Test
    void invalidJavaNamesMustFailBeforeCreatingOutput() {
        for (List<String> options : List.of(List.of("--projectName", "123-game"),
                List.of("--projectName", "---"), List.of("--packageName", "group.class.game"),
                List.of("--packageName", "group._.game"))) {
            reject(options);
        }
    }

    @Test
    void unknownDuplicateAndConflictingOptionsMustFailBeforeCreatingOutput() {
        for (List<String> options : List.of(List.of("--componets", "redis"),
                List.of("--components", "actor", "--COMPONENTS", "redis"),
                List.of("--fromKeywords", "rpg", "--from-keywords", "rpg"),
                List.of("--force", "--force"), List.of("--components"),
                List.of("--help", "--componets", "redis"), List.of("--help", "--listTemplates"),
                List.of("--components", "unknown"), List.of("--components", "actor,"))) {
            reject(options);
        }
    }

    @Test
    void validNamesAndComponentAliasGenerateExpectedSources() throws Exception {
        Path output = temporary.resolve("valid");
        ProjectScaffoldCli.main(new String[] {"--template", "runtime", "--projectName", "game-123",
                "--packageName", "group.example.game", "--components", "actor", "--templateRoot", "../templates",
                "--outputDir", output.toString()});
        assertTrue(Files.exists(output.resolve("src/main/java/group/example/game/Game123Application.java")));
        assertTrue(Files.readString(output.resolve("zero-scaffold.json")).contains("zero.actor.scheduler"));
        ProjectScaffoldCli.main(new String[] {"--describe-template", "runtime"});
    }

    private void reject(final List<String> options) {
        Path output = temporary.resolve("invalid");
        var arguments = new ArrayList<>(List.of("--outputDir", output.toString(), "--templateRoot", "../templates"));
        arguments.addAll(options);
        assertThrows(IllegalArgumentException.class, () -> ProjectScaffoldCli.main(arguments.toArray(String[]::new)));
        assertFalse(Files.exists(output));
    }
}
