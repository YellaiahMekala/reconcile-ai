// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.application;

import com.reconcileai.application.audit.AuditRecorder;
import com.reconcileai.application.audit.AuditRecorder.AuditEntry;
import com.reconcileai.domain.BreakItem;
import com.reconcileai.domain.BreakSource;
import com.reconcileai.domain.Money;
import com.reconcileai.infrastructure.messaging.OutboxRecorder;
import com.reconcileai.infrastructure.persistence.BreakItemRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * Creates a break from a trusted system channel (Kafka intake), where there is no caller JWT: the tenant is named in
 * the message and validated for shape. Idempotent on (tenant, breakReference), so redelivery is harmless.
 *
 * <p>Trust boundary: the topic ACL decides who may publish a tenant id. The REST path never uses this class; it keeps
 * taking the tenant from the JWT only.
 */
@Service
public class BreakIngestionService {

    private static final Pattern TENANT = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final String ACTOR = "system:kafka-intake";

    public enum Outcome { CREATED, DUPLICATE }

    private final BreakItemRepository repository;
    private final AuditRecorder audit;
    private final OutboxRecorder outbox;

    public BreakIngestionService(BreakItemRepository repository, AuditRecorder audit, OutboxRecorder outbox) {
        this.repository = repository;
        this.audit = audit;
        this.outbox = outbox;
    }

    /** @throws IllegalArgumentException for a malformed message (the consumer dead-letters these without retry) */
    @Transactional
    public Outcome ingest(String tenantId, String breakReference, String ledger, String external, String currency) {
        if (tenantId == null || !TENANT.matcher(tenantId).matches()) {
            throw new IllegalArgumentException("invalid tenantId");
        }
        if (breakReference == null || breakReference.isBlank() || breakReference.length() > 128) {
            throw new IllegalArgumentException("invalid breakReference");
        }
        Money l = Money.of(ledger, currency);
        Money e = Money.of(external, currency);
        if (repository.existsByTenantIdAndBreakReference(tenantId, breakReference)) {
            return Outcome.DUPLICATE;
        }
        BreakItem item = repository.save(new BreakItem(tenantId, breakReference, l, e, BreakSource.KAFKA));
        Map<String, Object> details = Map.of("breakReference", breakReference,
            "variance", item.variance().amount().toPlainString(), "currency", item.getCurrencyCode());
        audit.record(new AuditEntry(tenantId, "BREAK_CREATED", "BREAK", item.getId(), ACTOR, details));
        outbox.record(tenantId, "BREAK", item.getId(), "BREAK_CREATED", details);
        return Outcome.CREATED;
    }
}
