// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.rero;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Platform Q3.5 as build-breaking source checks (same style as ResolutionPathArchitectureTest and
 * KnowledgeArchitectureTest): nothing on the RERO path can approve, resolve or write approval state. RERO's answer
 * only reaches the policy gate through InvestigationJobRunner's shared verify/route/persist step.
 */
class ReroArchitectureTest {

    private static final Path MAIN = Path.of("src/main/java/com/reconcileai");
    private static final List<Path> RERO_SOURCES = List.of(
        MAIN.resolve("application/rero"), MAIN.resolve("infrastructure/rero"), MAIN.resolve("api/ReroInternalController.java"));
    private static final List<String> FORBIDDEN = List.of(
        "ResolutionCommitService", "ApprovalService", "ApprovalTaskRepository", "application.approval",
        "ApprovalController", "commitResolution", "markResolved", "ProposalService", "PolicyGateService",
        "RoutingDecision", "markRouted");

    @Test
    void noReroClassDependsOnApprovalResolutionOrRouting() {
        for (Path p : javaFiles()) {
            String src = read(p);
            for (String forbidden : FORBIDDEN) {
                assertThat(src).as(p.getFileName() + " must not reference " + forbidden).doesNotContain(forbidden);
            }
        }
    }

    @Test
    void theInternalApiHasNoWriteMappingInSource() {
        String controller = read(MAIN.resolve("api/ReroInternalController.java"));
        assertThat(controller).doesNotContain("@PostMapping").doesNotContain("@PutMapping")
            .doesNotContain("@PatchMapping").doesNotContain("@DeleteMapping").doesNotContain("RequestMethod.");
    }

    @Test
    void runTokensAndPemsAreNeverLogged() {
        for (Path p : javaFiles()) {
            for (String line : read(p).split("\n")) {
                if (line.contains("log.")) {
                    assertThat(line).as(p.getFileName().toString())
                        .doesNotContainIgnoringCase("token").doesNotContainIgnoringCase("pem");
                }
            }
        }
    }

    private static List<Path> javaFiles() {
        return RERO_SOURCES.stream().flatMap(root -> {
            try {
                return Files.isDirectory(root) ? walk(root) : Stream.of(root);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }).toList();
    }

    private static Stream<Path> walk(Path root) throws IOException {
        try (Stream<Path> s = Files.walk(root)) {
            return s.filter(p -> p.toString().endsWith(".java")).toList().stream();
        }
    }

    private static String read(Path p) {
        try {
            return Files.readString(p);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
