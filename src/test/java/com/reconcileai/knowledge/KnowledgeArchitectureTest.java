package com.reconcileai.knowledge;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Platform TDD 4.1 as build-breaking checks (plain source scan, like {@code ResolutionPathArchitectureTest}):
 * <ul>
 *   <li>reconcile-ai calls EKA search only, never EKA chat or conversations;</li>
 *   <li>only {@code EkaKnowledgeClient} talks to EKA;</li>
 *   <li>the agent modes never reach EKA: knowledge is a deterministic pre-step of the single-call mode (ADR-Q1).</li>
 * </ul>
 */
class KnowledgeArchitectureTest {

    private static final Path MAIN = Path.of("src/main/java");
    private static final Path KNOWLEDGE = MAIN.resolve("com/reconcileai/infrastructure/knowledge");

    @Test
    void theKnowledgeAdapterCallsSearchOnly() throws IOException {
        for (Path p : javaFiles(KNOWLEDGE)) {
            String src = Files.readString(p);
            assertThat(src).as(p.getFileName().toString())
                .doesNotContain("/api/v1/chat").doesNotContain("/api/v1/conversations");
        }
        assertThat(Files.readString(KNOWLEDGE.resolve("EkaKnowledgeClient.java")))
            .contains("\"/api/v1/search\"");
    }

    @Test
    void onlyTheEkaClientHoldsTheSearchPath() throws IOException {
        List<String> holders = javaFiles(MAIN).stream()
            .filter(p -> read(p).contains("\"/api/v1/search\""))      // string literals, not Javadoc mentions
            .map(p -> p.getFileName().toString())
            .toList();
        assertThat(holders).containsExactly("EkaKnowledgeClient.java");
    }

    @Test
    void agentModesNeverUseKnowledge() throws IOException {
        for (Path p : javaFiles(MAIN.resolve("com/reconcileai/infrastructure/ai/agent"))) {
            assertThat(read(p)).as(p.getFileName().toString())
                .doesNotContain("application.knowledge").doesNotContain("infrastructure.knowledge");
        }
    }

    private static List<Path> javaFiles(Path root) throws IOException {
        try (Stream<Path> s = Files.walk(root)) {
            return s.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    private static String read(Path p) {
        try {
            return Files.readString(p);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
