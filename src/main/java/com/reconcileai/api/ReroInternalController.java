// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.api;

import com.reconcileai.application.rero.ReroToolException;
import com.reconcileai.application.rero.ReroToolService;
import com.reconcileai.application.rero.ReroToolService.Caller;
import com.reconcileai.infrastructure.rero.RunTokens;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Map;
import java.util.UUID;

/**
 * RERO's read-only tools (platform Q3.2, Contract C section 2). Reached only through the internal security chain with
 * a run token; every mapping is a GET ({@code InternalApiReadOnlyTest} keeps it that way). The tenant comes from the
 * token, the break from the job row; no parameter can name either.
 */
@RestController
@RequestMapping("/internal/rero/v1/jobs/{jobId}")
public class ReroInternalController {

    private final ReroToolService tools;

    public ReroInternalController(ReroToolService tools) {
        this.tools = tools;
    }

    @GetMapping("/break")
    public ReroToolService.BreakView breakSummary(@AuthenticationPrincipal Jwt token, @PathVariable UUID jobId) {
        return tools.breakSummary(caller(token), jobId);
    }

    @GetMapping("/evidence")
    public ReroToolService.EvidenceView evidence(@AuthenticationPrincipal Jwt token, @PathVariable UUID jobId,
                                                 @RequestParam(name = "query", required = false) String query) {
        return tools.evidence(caller(token), jobId, query);
    }

    @GetMapping("/similar-breaks")
    public ReroToolService.SimilarView similarBreaks(@AuthenticationPrincipal Jwt token, @PathVariable UUID jobId) {
        return tools.similarBreaks(caller(token), jobId);
    }

    @GetMapping("/knowledge")
    public ReroToolService.KnowledgeView knowledge(@AuthenticationPrincipal Jwt token, @PathVariable UUID jobId,
                                                   @RequestParam(name = "query", required = false) String query) {
        return tools.knowledge(caller(token), jobId, query);
    }

    private static Caller caller(Jwt token) {
        // The decoder already required both claims; a malformed job id simply cannot match any path.
        UUID job;
        try {
            job = UUID.fromString(token.getClaimAsString(RunTokens.JOB_CLAIM));
        } catch (RuntimeException e) {
            job = null;
        }
        return new Caller(token.getClaimAsString(RunTokens.TENANT_CLAIM), job);
    }

    /** Contract C error body {@code {"code","message"}} for this controller only; ahead of the global handler. */
    @RestControllerAdvice(assignableTypes = ReroInternalController.class)
    @Order(Ordered.HIGHEST_PRECEDENCE)
    static class Errors {

        @ExceptionHandler(ReroToolException.class)
        ResponseEntity<Map<String, String>> refused(ReroToolException e) {
            return ResponseEntity.status(e.status()).body(Map.of("code", e.code(), "message", e.getMessage()));
        }

        @ExceptionHandler(MethodArgumentTypeMismatchException.class)
        ResponseEntity<Map<String, String>> badJobId(MethodArgumentTypeMismatchException e) {
            return ResponseEntity.status(403).body(Map.of("code", ReroToolException.JOB_MISMATCH,
                "message", "The run token does not grant access to this job"));
        }

        @ExceptionHandler(RuntimeException.class)
        ResponseEntity<Map<String, String>> failed(RuntimeException e) {
            return ResponseEntity.status(503).body(Map.of("code", "TOOL_UNAVAILABLE", "message", "The tool failed"));
        }
    }
}
