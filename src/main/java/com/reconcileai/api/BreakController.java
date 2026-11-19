// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.api;

import com.reconcileai.api.dto.BreakResponse;
import com.reconcileai.api.dto.CreateBreakRequest;
import com.reconcileai.application.BreakService;
import com.reconcileai.domain.BreakItem;
import com.reconcileai.domain.Money;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.UUID;

/**
 * REST API for reconciliation breaks.
 *
 * <p>Controllers stay thin: validate input shape, check role, translate DTO <-> domain. They never
 * read a tenant from the request - that happens in the service via TenantContext.
 *
 * <table>
 *   <tr><th>Endpoint</th><th>Roles</th><th>Codes</th></tr>
 *   <tr><td>POST /api/v1/breaks</td><td>RECON_ANALYST</td><td>201, 400, 401, 403, 409</td></tr>
 *   <tr><td>GET  /api/v1/breaks</td><td>any app role</td><td>200, 401, 403</td></tr>
 *   <tr><td>GET  /api/v1/breaks/{id}</td><td>any app role</td><td>200, 401, 403, 404</td></tr>
 * </table>
 */
@RestController
@RequestMapping("/api/v1/breaks")
public class BreakController {

    private static final String ANY_APP_ROLE =
        "hasAnyRole('RECON_ANALYST','APPROVER_L1','APPROVER_L2','SRE_ADMIN')";

    private final BreakService breakService;

    public BreakController(BreakService breakService) {
        this.breakService = breakService;
    }

    @PostMapping
    @PreAuthorize("hasRole('RECON_ANALYST')")
    public ResponseEntity<BreakResponse> create(@Valid @RequestBody CreateBreakRequest req,
                                                UriComponentsBuilder uriBuilder) {
        // Amounts arrive as strings and go straight to BigDecimal - never through double.
        Money ledger = Money.of(req.ledgerAmount(), req.currency());
        Money external = Money.of(req.externalAmount(), req.currency());
        BreakItem created = breakService.createBreak(req.breakReference(), ledger, external);
        URI location = uriBuilder.path("/api/v1/breaks/{id}").buildAndExpand(created.getId()).toUri();
        return ResponseEntity.created(location).body(BreakResponse.from(created));
    }

    /** Lists ONLY the caller's tenant's breaks. Returns a plain list (no Page JSON) to keep the contract stable. */
    @GetMapping
    @PreAuthorize(ANY_APP_ROLE)
    public ResponseEntity<List<BreakResponse>> list(@RequestParam(defaultValue = "0") int page,
                                                    @RequestParam(defaultValue = "20") int size) {
        List<BreakResponse> body = breakService.listBreaks(page, size).stream()
            .map(BreakResponse::from)
            .toList();
        return ResponseEntity.ok(body);
    }

    @GetMapping("/{id}")
    @PreAuthorize(ANY_APP_ROLE)
    public ResponseEntity<BreakResponse> get(@PathVariable UUID id) {
        return ResponseEntity.ok(BreakResponse.from(breakService.getBreak(id)));
    }
}
