// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.infrastructure.persistence;

import com.reconcileai.domain.BreakItem;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Tenant-scoped access to {@link BreakItem} (NFR-02: isolation at the QUERY level).
 *
 * <h2>Why extend {@code Repository} and not {@code JpaRepository}?</h2>
 * {@code JpaRepository} would inherit {@code findById(UUID)}, {@code findAll()},
 * {@code deleteById(UUID)} and friends - all of which ignore tenant. Any future caller (human or
 * AI assistant) could use them and leak another tenant's data. By extending the empty marker
 * interface {@link Repository}, the ONLY methods that exist are the ones declared below, and every
 * read requires a {@code tenantId}. Isolation is enforced by the compiler, not by code review.
 *
 * <h2>Why not a Hibernate @Filter / Postgres RLS?</h2>
 * Both are valid hardening layers for later. Explicit tenant parameters are chosen
 * because they are visible in every call site, trivially testable, and cannot be "forgotten" by
 * a missing filter activation.
 */
public interface BreakItemRepository extends Repository<BreakItem, UUID> {

    /** Insert or update. Tenant is already fixed on the entity (column is updatable=false). */
    BreakItem save(BreakItem breakItem);

    /** Returns empty for a row owned by another tenant - callers map that to 404. */
    Optional<BreakItem> findByIdAndTenantId(UUID id, String tenantId);

    /** Tenant's breaks, newest first. Pageable caps the result size (see BreakService.MAX_PAGE_SIZE). */
    List<BreakItem> findByTenantIdOrderByCreatedAtDesc(String tenantId, Pageable pageable);

    /** Uniqueness of break_reference is per tenant; two tenants may reuse the same reference. */
    boolean existsByTenantIdAndBreakReference(String tenantId, String breakReference);
}
