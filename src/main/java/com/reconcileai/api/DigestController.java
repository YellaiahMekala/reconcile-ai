// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.api;

import com.reconcileai.application.NotFoundException;
import com.reconcileai.application.digest.DailyDigestService;
import com.reconcileai.application.digest.DailyDigestService.Digest;
import com.reconcileai.infrastructure.security.TenantContext;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

/**
 * <pre>
 *  GET  /api/v1/digests?days=7                 approvers, SRE   most recent digests of the caller's tenant
 *  GET  /api/v1/digests/{yyyy-MM-dd}           approvers, SRE   one day (404 if not generated)
 *  POST /api/v1/digests/{yyyy-MM-dd}/generate  SRE_ADMIN        (re)build one past day for the caller's tenant
 * </pre>
 */
@RestController
public class DigestController {

    private final DailyDigestService digests;
    private final TenantContext tenants;

    public DigestController(DailyDigestService digests, TenantContext tenants) {
        this.digests = digests;
        this.tenants = tenants;
    }

    @GetMapping("/api/v1/digests")
    @PreAuthorize("hasAnyRole('APPROVER_L1','APPROVER_L2','SRE_ADMIN')")
    public List<Digest> recent(@RequestParam(defaultValue = "7") int days) {
        return digests.recent(tenants.requireTenantId(), Math.max(1, Math.min(days, 90)));
    }

    @GetMapping("/api/v1/digests/{day}")
    @PreAuthorize("hasAnyRole('APPROVER_L1','APPROVER_L2','SRE_ADMIN')")
    public Digest one(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day) {
        return digests.find(tenants.requireTenantId(), day)
            .orElseThrow(() -> new NotFoundException("DIGEST_NOT_FOUND", "No digest for " + day));
    }

    @PostMapping("/api/v1/digests/{day}/generate")
    @PreAuthorize("hasRole('SRE_ADMIN')")
    public Digest generate(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day) {
        if (!day.isBefore(LocalDate.now(ZoneOffset.UTC).plusDays(1))) {
            throw new IllegalArgumentException("digest day must not be in the future");
        }
        return digests.generate(tenants.requireTenantId(), day);
    }
}
