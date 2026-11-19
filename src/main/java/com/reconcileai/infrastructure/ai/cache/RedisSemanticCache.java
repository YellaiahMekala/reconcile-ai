// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.infrastructure.ai.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reconcileai.infrastructure.ai.guard.InjectionScanner;
import com.reconcileai.infrastructure.ai.pii.PiiMasker;
import com.reconcileai.infrastructure.ai.prompt.InvestigationPromptBuilder.BuiltPrompt;
import com.reconcileai.infrastructure.ai.prompt.ProposalDraft;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Two-tier cache in Redis, scoped per tenant.
 *
 * <pre>
 *  lookup ─► analyst note suspect?            ──► miss (let the input guard block it; never answer from cache)
 *         ─► EXACT:    key = sha256(namespace | system | user)          ──► hit
 *         ─► SEMANTIC: embed masked prompt, cosine against the tenant's newest N entries
 *                      AND the entry's evidence snapshot (sorted chunk ids) equals this prompt's ──► hit if >= threshold
 *         ─► miss
 * </pre>
 *
 * <h2>Why the evidence-snapshot rule</h2>
 * A similar question over different evidence is a different question. Requiring the identical chunk-id set means a
 * cached draft can only cite chunks that are in THIS retrieval snapshot, so citation verification stays meaningful,
 * and new evidence always forces a fresh model call. The policy gate recomputes routing from the current break.
 *
 * <h2>Keys</h2>
 * <pre>
 *  rai:{ns}:{tenant}:exact:{sha256}  -> entry id        (TTL)
 *  rai:{ns}:{tenant}:e:{entryId}     -> hash vec|draft|chunks   (TTL)
 *  rai:{ns}:{tenant}:idx             -> zset entryId by time (bounded to max-entries-per-tenant)
 * </pre>
 * Semantic search is a bounded linear scan (newest N) rather than a vector index: no Redis Stack module needed, and
 * at portfolio scale N=500 is sub-millisecond of arithmetic. A RediSearch KNN index is the upgrade path.
 *
 * <p>Fail open: any Redis or embedding error is a miss (and a metric), never a failed investigation.
 * The embedding is computed on PII-masked text; prompts themselves are never stored.
 */
public class RedisSemanticCache implements SemanticCache {

    private static final Logger log = LoggerFactory.getLogger(RedisSemanticCache.class);

    private final StringRedisTemplate redis;
    private final EmbeddingModel embeddings;
    private final InjectionScanner scanner;
    private final SemanticCacheProperties props;
    private final MeterRegistry meters;
    private final ObjectMapper json;

    public RedisSemanticCache(StringRedisTemplate redis, EmbeddingModel embeddings, InjectionScanner scanner,
                              SemanticCacheProperties props, MeterRegistry meters, ObjectMapper json) {
        this.redis = redis;
        this.embeddings = embeddings;
        this.scanner = scanner;
        this.props = props;
        this.meters = meters;
        this.json = json;
    }

    @Override
    public Optional<Hit> lookup(String tenantId, BuiltPrompt prompt, String analystNote) {
        if (analystNote != null && scanner.isSuspect(analystNote)) {
            count("bypass_suspect_note");
            return Optional.empty();
        }
        try {
            String exactId = redis.opsForValue().get(exactKey(tenantId, prompt));
            if (exactId != null && snapshot(prompt).equals(redis.opsForHash().get(entryKey(tenantId, exactId), "chunks"))) {
                Optional<ProposalDraft> d = readDraft(tenantId, exactId);
                if (d.isPresent()) {
                    count("hit_exact");
                    return Optional.of(new Hit(d.get(), HitType.EXACT, 1.0));
                }
            }
            String snapshot = snapshot(prompt);
            float[] query = embed(prompt);
            Set<String> ids = redis.opsForZSet().reverseRange(idxKey(tenantId), 0, props.maxEntriesPerTenant() - 1L);
            String bestId = null;
            double best = -1;
            for (String id : ids == null ? Set.<String>of() : ids) {
                List<Object> fields = redis.opsForHash().multiGet(entryKey(tenantId, id), List.of("vec", "chunks"));
                if (fields.get(0) == null || !snapshot.equals(fields.get(1))) {
                    continue;
                }
                double sim = cosine(query, decode((String) fields.get(0)));
                if (sim > best) {
                    best = sim;
                    bestId = id;
                }
            }
            if (bestId != null && best >= props.similarityThreshold()) {
                Optional<ProposalDraft> d = readDraft(tenantId, bestId);
                if (d.isPresent()) {
                    count("hit_semantic");
                    meters.summary("reconcile.llm.cache.similarity").record(best);
                    return Optional.of(new Hit(d.get(), HitType.SEMANTIC, best));
                }
            }
            count("miss");
            return Optional.empty();
        } catch (RuntimeException e) {
            log.warn("semantic cache lookup failed open: {}", e.toString());
            count("error");
            return Optional.empty();
        }
    }

    @Override
    public void store(String tenantId, BuiltPrompt prompt, ProposalDraft draft) {
        try {
            String id = UUID.randomUUID().toString();
            Duration ttl = Duration.ofHours(props.ttlHours());
            String entry = entryKey(tenantId, id);
            redis.opsForHash().putAll(entry, Map.of(
                "vec", encode(embed(prompt)),
                "chunks", snapshot(prompt),
                "draft", json.writeValueAsString(draft)));
            redis.expire(entry, ttl);
            redis.opsForValue().set(exactKey(tenantId, prompt), id, ttl);
            String idx = idxKey(tenantId);
            redis.opsForZSet().add(idx, id, System.currentTimeMillis());
            redis.opsForZSet().removeRange(idx, 0, -1L - props.maxEntriesPerTenant()); // keep newest N
            redis.expire(idx, ttl);
            count("store");
        } catch (RuntimeException | JsonProcessingException e) {
            log.warn("semantic cache store skipped: {}", e.toString());
            count("error");
        }
    }

    private Optional<ProposalDraft> readDraft(String tenantId, String id) {
        Object raw = redis.opsForHash().get(entryKey(tenantId, id), "draft");
        if (raw == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(json.readValue((String) raw, ProposalDraft.class));
        } catch (JsonProcessingException e) {
            return Optional.empty();
        }
    }

    private float[] embed(BuiltPrompt prompt) {
        return embeddings.embed(PiiMasker.mask(prompt.user()).text());
    }

    static String snapshot(BuiltPrompt prompt) {
        return String.join(",", new TreeSet<>(prompt.includedChunkIds()));
    }

    private String exactKey(String tenantId, BuiltPrompt p) {
        return prefix(tenantId) + "exact:" + sha256(props.namespace() + "\u0000" + p.system() + "\u0000" + p.user());
    }

    private String entryKey(String tenantId, String id) {
        return prefix(tenantId) + "e:" + id;
    }

    private String idxKey(String tenantId) {
        return prefix(tenantId) + "idx";
    }

    private String prefix(String tenantId) {
        return "rai:" + props.namespace() + ":" + tenantId + ":";
    }

    private void count(String result) {
        meters.counter("reconcile.llm.cache", "result", result).increment();
    }

    static double cosine(float[] a, float[] b) {
        if (a.length != b.length) {
            return -1;
        }
        double dot = 0;
        double na = 0;
        double nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        return na == 0 || nb == 0 ? 0 : dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    static String encode(float[] v) {
        ByteBuffer buf = ByteBuffer.allocate(v.length * 4);
        for (float f : v) {
            buf.putFloat(f);
        }
        return Base64.getEncoder().encodeToString(buf.array());
    }

    static float[] decode(String s) {
        ByteBuffer buf = ByteBuffer.wrap(Base64.getDecoder().decode(s));
        float[] v = new float[buf.remaining() / 4];
        for (int i = 0; i < v.length; i++) {
            v[i] = buf.getFloat();
        }
        return v;
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
