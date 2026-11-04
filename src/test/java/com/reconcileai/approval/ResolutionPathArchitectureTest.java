package com.reconcileai.approval;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locked invariant as a build-breaking test: RESOLVED is reachable from exactly one class.
 * A plain source scan (no ArchUnit dependency): if anyone - human or AI assistant - adds a second caller of
 * markResolved() or markApproved(), `mvn test` fails with the offending file names.
 */
class ResolutionPathArchitectureTest {

    private static final Path MAIN = Path.of("src/main/java");

    @Test
    void onlyResolutionCommitServiceResolvesBreaks() throws IOException {
        assertThat(callersOf(".markResolved(")).containsExactly("ResolutionCommitService.java");
        assertThat(callersOf(".markApproved(")).containsExactly("ResolutionCommitService.java");
    }

    @Test
    void resolutionCommitServiceIsNotPublic() throws IOException {
        String src = Files.readString(MAIN.resolve("com/reconcileai/application/approval/ResolutionCommitService.java"));
        assertThat(src).contains("\nclass ResolutionCommitService").doesNotContain("public class ResolutionCommitService");
    }

    @Test
    void investigationPackageNeverTouchesTheCommitPath() throws IOException {
        try (Stream<Path> files = Files.walk(MAIN.resolve("com/reconcileai/application/investigation"))) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                assertThat(Files.readString(f)).as(f.toString()).doesNotContain("ResolutionCommitService");
            }
        }
    }

    private static List<String> callersOf(String call) throws IOException {
        try (Stream<Path> files = Files.walk(MAIN)) {
            return files.filter(p -> p.toString().endsWith(".java"))
                .filter(p -> {
                    try {
                        return Files.readString(p).contains(call);
                    } catch (IOException e) {
                        throw new IllegalStateException(e);
                    }
                })
                .map(p -> p.getFileName().toString())
                .sorted()
                .toList();
        }
    }
}
