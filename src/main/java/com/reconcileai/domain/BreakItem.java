package com.reconcileai.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;

/**
 * A reconciliation break: a mismatch between an internal ledger amount and an
 * external counterparty amount, scoped to a tenant.
 *
 * <p>Money is stored as amount + currency columns and reconstituted into the {@link Money}
 * value object via accessors — JPA cannot map a record directly as an @Embeddable cleanly
 * across both fields, so the primitive columns are the source of truth and Money is derived.
 *
 * <p>{@code @Version} gives optimistic locking (NFR-05). The materiality figure used by the
 * policy gate is the <em>variance</em> — the absolute ledger/external difference —
 * never the raw ledger amount.
 */
@Entity
@Table(name = "break_item")
public class BreakItem {

    @Id
    @GeneratedValue
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private String tenantId;

    @Column(name = "break_reference", nullable = false)
    private String breakReference;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private BreakStatus status;

    @Column(name = "ledger_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal ledgerAmount;

    @Column(name = "external_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal externalAmount;

    // V1 declares CHAR(3). Postgres reports it as bpchar/Types.CHAR; without @JdbcTypeCode(CHAR) Hibernate's
    // ddl-auto=validate expects VARCHAR and can fail startup with "found [bpchar], but expecting [varchar(3)]".
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private BreakSource source;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected BreakItem() { // JPA
    }

    public BreakItem(String tenantId, String breakReference,
                     Money ledgerAmount, Money externalAmount, BreakSource source) {
        if (!ledgerAmount.currency().equals(externalAmount.currency())) {
            throw new IllegalArgumentException("Ledger and external amounts must share a currency");
        }
        this.tenantId = tenantId;
        this.breakReference = breakReference;
        this.ledgerAmount = ledgerAmount.amount();
        this.externalAmount = externalAmount.amount();
        this.currencyCode = ledgerAmount.currency().getCurrencyCode();
        this.source = source;
        this.status = BreakStatus.OPEN;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    /** Materiality basis for the policy gate: |ledger - external|, never the raw ledger figure. */
    public Money variance() {
        return ledgerMoney().absoluteDifference(externalMoney());
    }

    public Money ledgerMoney() {
        return new Money(ledgerAmount, Currency.getInstance(currencyCode));
    }

    public Money externalMoney() {
        return new Money(externalAmount, Currency.getInstance(currencyCode));
    }

    // Transitions live on the entity so illegal state changes fail in one place, whatever the caller.

    public void markEvidenceProcessing() {
        requireAcceptingEvidence();
        this.status = BreakStatus.EVIDENCE_PROCESSING;
    }

    /**
     * Evidence cannot change while an investigation or approval is in flight, or after resolution:
     * the proposal an approver signs must be based on exactly the evidence that was retrieved.
     */
    public boolean canAcceptEvidence() {
        return switch (status) {
            case INVESTIGATING, PENDING_L1, PENDING_L2, RESOLVED, AUTO_RESOLVED -> false;
            default -> true;
        };
    }

    public void requireAcceptingEvidence() {
        if (!canAcceptEvidence()) {
            throw new IllegalBreakStateException(id, status, "accept evidence");
        }
    }

    /**
     * Recomputes break status after an evidence item settles (READY or FAILED).
     * Readiness is derived from EVIDENCE statuses, never by mixing the two enums.
     *
     * @param readyCount      evidence rows for this break in READY
     * @param processingCount evidence rows still PROCESSING
     */
    public void onEvidenceSettled(long readyCount, long processingCount) {
        if (!canAcceptEvidence()) {
            return; // never downgrade an in-flight/terminal break
        }
        if (processingCount > 0) {
            this.status = BreakStatus.EVIDENCE_PROCESSING;
        } else if (readyCount > 0) {
            this.status = BreakStatus.READY_TO_INVESTIGATE;
        } else {
            this.status = BreakStatus.OPEN; // all evidence failed: analyst must upload something usable
        }
    }


    /** Re-investigation is allowed after rejection, manual review, or a failed job. */
    public boolean canStartInvestigation() {
        return switch (status) {
            case READY_TO_INVESTIGATE, REJECTED, MANUAL_REVIEW_REQUIRED, FAILED_RETRYABLE -> true;
            default -> false;
        };
    }

    public void markInvestigating() {
        if (!canStartInvestigation()) {
            throw new IllegalBreakStateException(id, status, "start an investigation");
        }
        this.status = BreakStatus.INVESTIGATING;
    }

    /** Applies the policy gate's route. AUTO_RESOLVE never lands here: it goes through ResolutionCommitService. */
    public void markRouted(RoutingDecision route) {
        if (status != BreakStatus.INVESTIGATING) {
            throw new IllegalBreakStateException(id, status, "be routed");
        }
        this.status = switch (route) {
            case L1_APPROVAL -> BreakStatus.PENDING_L1;
            case L2_APPROVAL -> BreakStatus.PENDING_L2;
            case MANUAL_REVIEW -> BreakStatus.MANUAL_REVIEW_REQUIRED;
            case AUTO_RESOLVE -> throw new IllegalArgumentException("AUTO_RESOLVE must go through ResolutionCommitService");
        };
    }

    /**
     * The governance gateway refused the content or its data level (platform Q2.5). Not a technical failure: the
     * same input would be refused again, so the break waits for a person instead of the retry sweeper. No proposal
     * exists; the reason is on the job row and in the audit entry.
     */
    public void markPolicyBlocked() {
        if (status == BreakStatus.INVESTIGATING) {
            this.status = BreakStatus.MANUAL_REVIEW_REQUIRED;
        }
    }

    /** Technical failure (model down, parse error, restart): retryable, never silently resolved. */
    public void markInvestigationFailed() {
        if (status == BreakStatus.INVESTIGATING) {
            this.status = BreakStatus.FAILED_RETRYABLE;
        }
    }


    /**
     * The ONLY transition to RESOLVED. By convention (and enforced by ResolutionPathArchitectureTest) it is called
     * from exactly one class: application.approval.ResolutionCommitService.
     */
    public void markResolved() {
        if (status != BreakStatus.PENDING_L1 && status != BreakStatus.PENDING_L2) {
            throw new IllegalBreakStateException(id, status, "be resolved");
        }
        this.status = BreakStatus.RESOLVED;
    }

    /** Checker rejected the proposal: back to the analyst, who may add evidence and re-investigate. */
    public void markProposalRejected() {
        if (status != BreakStatus.PENDING_L1 && status != BreakStatus.PENDING_L2) {
            throw new IllegalBreakStateException(id, status, "have its proposal rejected");
        }
        this.status = BreakStatus.REJECTED;
    }

    public UUID getId() { return id; }
    public String getTenantId() { return tenantId; }
    public String getBreakReference() { return breakReference; }
    public BreakStatus getStatus() { return status; }
    public BigDecimal getLedgerAmount() { return ledgerAmount; }
    public BigDecimal getExternalAmount() { return externalAmount; }
    public String getCurrencyCode() { return currencyCode; }
    public BreakSource getSource() { return source; }
    public long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
