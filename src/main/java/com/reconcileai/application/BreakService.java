package com.reconcileai.application;

import com.reconcileai.application.audit.AuditRecorder;
import com.reconcileai.application.audit.AuditRecorder.AuditEntry;
import com.reconcileai.domain.BreakItem;
import com.reconcileai.domain.BreakSource;
import com.reconcileai.domain.Money;
import com.reconcileai.infrastructure.persistence.BreakItemRepository;
import com.reconcileai.infrastructure.messaging.OutboxRecorder;
import com.reconcileai.infrastructure.security.TenantContext;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Use cases for reconciliation breaks (FR-01 create, FR-02 view).
 *
 * <p><b>Tenant rule:</b> every method resolves the tenant from {@link TenantContext} (the validated
 * JWT) as its first line. No method accepts a tenantId parameter, so a controller cannot pass a
 * client-supplied tenant even by mistake.
 *
 * <p><b>Not found vs forbidden:</b> a break owned by another tenant is reported as
 * {@link BreakNotFoundException} (404). Returning 403 would tell an attacker "this UUID exists,
 * you just can't see it" - an existence oracle across tenants.
 */
@Service
public class BreakService {

    /** Hard cap on list size so one request cannot pull a tenant's entire table. */
    static final int MAX_PAGE_SIZE = 100;

    private final BreakItemRepository repository;
    private final TenantContext tenantContext;
    private final AuditRecorder audit;
    private final OutboxRecorder outbox;

    public BreakService(BreakItemRepository repository, TenantContext tenantContext, AuditRecorder audit,
                        OutboxRecorder outbox) {
        this.outbox = outbox;
        this.repository = repository;
        this.tenantContext = tenantContext;
        this.audit = audit;
    }

    /**
     * Creates an OPEN break for the caller's tenant.
     *
     * <p>The exists-check gives a friendly 409 in the common case; the DB unique constraint
     * (tenant_id, break_reference) remains the real guarantee under concurrency and is mapped
     * to 409 as well in GlobalExceptionHandler.
     */
    @Transactional
    public BreakItem createBreak(String breakReference, Money ledger, Money external) {
        String tenantId = tenantContext.requireTenantId();
        if (repository.existsByTenantIdAndBreakReference(tenantId, breakReference)) {
            throw new DuplicateBreakReferenceException(breakReference);
        }
        BreakItem item = repository.save(new BreakItem(tenantId, breakReference, ledger, external, BreakSource.REST));
        // Same transaction as the insert: the audit entry exists if and only if the break does.
        Map<String, Object> details = Map.of("breakReference", breakReference,
            "variance", item.variance().amount().toPlainString(), "currency", item.getCurrencyCode());
        audit.record(new AuditEntry(tenantId, "BREAK_CREATED", "BREAK", item.getId(), tenantContext.requireSubject(), details));
        outbox.record(tenantId, "BREAK", item.getId(), "BREAK_CREATED", details);
        return item;
    }

    /** Fetch one break; 404 if absent OR owned by another tenant (indistinguishable by design). */
    @Transactional(readOnly = true)
    public BreakItem getBreak(UUID id) {
        String tenantId = tenantContext.requireTenantId();
        return repository.findByIdAndTenantId(id, tenantId)
            .orElseThrow(() -> new BreakNotFoundException(id));
    }

    /** Newest-first page of the caller's breaks. Size is clamped to [1, MAX_PAGE_SIZE]. */
    @Transactional(readOnly = true)
    public List<BreakItem> listBreaks(int page, int size) {
        String tenantId = tenantContext.requireTenantId();
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        return repository.findByTenantIdOrderByCreatedAtDesc(tenantId, PageRequest.of(safePage, safeSize));
    }
}
