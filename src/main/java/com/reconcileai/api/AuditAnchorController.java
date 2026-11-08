package com.reconcileai.api;

import com.reconcileai.application.audit.anchor.AuditAnchorService;
import com.reconcileai.application.audit.anchor.AuditAnchorService.AnchorVerification;
import com.reconcileai.application.audit.anchor.AuditAnchorService.AnchorView;
import com.reconcileai.infrastructure.security.TenantContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * <pre>
 *  GET /api/v1/audit/anchors          SRE_ADMIN   the caller tenant's anchors (newest first)
 *  GET /api/v1/audit/anchors/verify   SRE_ADMIN   check every anchor against the immutable copy and the live chain
 * </pre>
 * Present only when {@code reconcile.audit.anchor.enabled=true}. Tenant comes only from the JWT.
 */
@RestController
@ConditionalOnProperty(prefix = "reconcile.audit.anchor", name = "enabled", havingValue = "true")
public class AuditAnchorController {

    private final AuditAnchorService service;
    private final TenantContext tenantContext;

    public AuditAnchorController(AuditAnchorService service, TenantContext tenantContext) {
        this.service = service;
        this.tenantContext = tenantContext;
    }

    @GetMapping("/api/v1/audit/anchors")
    @PreAuthorize("hasRole('SRE_ADMIN')")
    public List<AnchorView> anchors() {
        return service.list(tenantContext.requireTenantId());
    }

    @GetMapping("/api/v1/audit/anchors/verify")
    @PreAuthorize("hasRole('SRE_ADMIN')")
    public AnchorVerification verify() {
        return service.verify(tenantContext.requireTenantId());
    }
}
