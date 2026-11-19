// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.infrastructure.ai.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import com.reconcileai.infrastructure.ai.guard.InjectionScanner;
import com.reconcileai.infrastructure.ai.prompt.InvestigationPromptBuilder.BuiltPrompt;
import com.reconcileai.infrastructure.ai.prompt.ProposalDraft;
import io.micrometer.core.instrument.MeterRegistry;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;

/**
 * D1.2.1: a small in-heap Caffeine L1 in front of the Redis cache ({@link RedisSemanticCache}, the L2).
 *
 * <pre>
 *  lookup ─► analyst note suspect?  ──► straight to L2 (which bypasses too): the guard must see it
 *         ─► L1 get(key)            ──► hit: no Redis round trip, no embedding call
 *         ─► L2 lookup              ──► hit: copy into L1, return
 *         ─► miss
 *  store  ─► L2 store, then L1 put (write-through)
 * </pre>
 *
 * <h2>Key</h2>
 * {@code tenant | sha256(namespace, system prompt, user prompt, evidence snapshot)}. The tenant is part of the key, so
 * no lookup can cross tenants; the evidence snapshot is part of the key, so the L2 evidence-snapshot rule holds for L1
 * as well (an L1 entry can only be served for the identical chunk-id set).
 *
 * <h2>Invalidation, consistent with L2</h2>
 * L2 has no delete path: entries end by TTL, by the namespace bump, or by the per-tenant bound. L1 follows the same
 * rules: the namespace is in the key (bump it and every L1 entry misses), the L1 TTL is never longer than the L2 TTL,
 * and L1 is bounded by size. Entries are immutable (a draft for an exact prompt and evidence set), so a replica's L1
 * cannot hold a value that disagrees with Redis; at most it keeps serving an entry up to the L1 TTL after Redis lost
 * it (flush or eviction). That window is the L1 TTL, minutes by default.
 */
public class TieredSemanticCache implements SemanticCache {

    private final SemanticCache l2;
    private final InjectionScanner scanner;
    private final String namespace;
    private final MeterRegistry meters;
    private final Cache<String, Hit> l1;

    public TieredSemanticCache(SemanticCache l2, InjectionScanner scanner, SemanticCacheProperties props,
                               SemanticCacheL1Properties l1Props, MeterRegistry meters, Ticker ticker) {
        this.l2 = l2;
        this.scanner = scanner;
        this.namespace = props.namespace();
        this.meters = meters;
        Duration ttl = l1Props.ttl().compareTo(Duration.ofHours(props.ttlHours())) > 0
            ? Duration.ofHours(props.ttlHours()) : l1Props.ttl();
        this.l1 = Caffeine.newBuilder()
            .maximumSize(l1Props.maxEntries())
            .expireAfterWrite(ttl)
            .ticker(ticker)
            .executor(Runnable::run) // evict on the caller thread: deterministic, no common-pool work
            .build();
    }

    @Override
    public Optional<Hit> lookup(String tenantId, BuiltPrompt prompt, String analystNote) {
        if (analystNote != null && scanner.isSuspect(analystNote)) {
            count("bypass");
            return l2.lookup(tenantId, prompt, analystNote); // L2 records the bypass and returns empty
        }
        String key = key(tenantId, prompt);
        Hit local = l1.getIfPresent(key);
        if (local != null) {
            count("l1_hit");
            return Optional.of(local);
        }
        Optional<Hit> remote = l2.lookup(tenantId, prompt, analystNote);
        if (remote.isPresent()) {
            l1.put(key, remote.get());
            count("l2_hit");
        } else {
            count("miss");
        }
        return remote;
    }

    @Override
    public void store(String tenantId, BuiltPrompt prompt, ProposalDraft draft) {
        l2.store(tenantId, prompt, draft);
        l1.put(key(tenantId, prompt), new Hit(draft, HitType.EXACT, 1.0));
    }

    /** Entries currently held (after pending evictions). For tests and diagnostics. */
    public long l1Size() {
        l1.cleanUp();
        return l1.estimatedSize();
    }

    private String key(String tenantId, BuiltPrompt p) {
        return tenantId + "|" + sha256(namespace + "\u0000" + p.system() + "\u0000" + p.user() + "\u0000"
            + RedisSemanticCache.snapshot(p));
    }

    private void count(String outcome) {
        meters.counter("reconcile.llm.cache.tier", "outcome", outcome).increment();
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
