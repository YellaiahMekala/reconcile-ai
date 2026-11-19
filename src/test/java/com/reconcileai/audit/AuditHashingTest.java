// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.audit;

import com.reconcileai.application.audit.AuditHashing;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AuditHashingTest {

    private static final UUID AGG = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final Instant T = Instant.parse("2026-09-17T10:15:30.123456789Z");

    @Test
    void detailsAreCanonicalRegardlessOfInsertionOrder() {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("b", 2);
        a.put("a", "x");
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("a", "x");
        b.put("b", 2);
        assertThat(AuditHashing.canonicalDetails(a)).isEqualTo(AuditHashing.canonicalDetails(b)).isEqualTo("{\"a\":\"x\",\"b\":2}");
    }

    @Test
    void hashIsStableAcrossNanosecondsPostgresCannotStore() {
        String h1 = AuditHashing.hash(AuditHashing.GENESIS, "T1", 1, "E", "BREAK", AGG, "u", "{}", T);
        String h2 = AuditHashing.hash(AuditHashing.GENESIS, "T1", 1, "E", "BREAK", AGG, "u", "{}", AuditHashing.normalize(T));
        assertThat(h1).isEqualTo(h2).hasSize(64);
    }

    @Test
    void anyFieldChangeChangesTheHash() {
        String base = AuditHashing.hash(AuditHashing.GENESIS, "T1", 1, "E", "BREAK", AGG, "u", "{}", T);
        assertThat(AuditHashing.hash(AuditHashing.GENESIS, "T1", 2, "E", "BREAK", AGG, "u", "{}", T)).isNotEqualTo(base);
        assertThat(AuditHashing.hash(AuditHashing.GENESIS, "T2", 1, "E", "BREAK", AGG, "u", "{}", T)).isNotEqualTo(base);
        assertThat(AuditHashing.hash(AuditHashing.GENESIS, "T1", 1, "E", "BREAK", AGG, "v", "{}", T)).isNotEqualTo(base);
        assertThat(AuditHashing.hash("f".repeat(64), "T1", 1, "E", "BREAK", AGG, "u", "{}", T)).isNotEqualTo(base);
        assertThat(AuditHashing.hash(AuditHashing.GENESIS, "T1", 1, "E", "BREAK", AGG, "u", "{\"x\":1}", T)).isNotEqualTo(base);
    }
}
