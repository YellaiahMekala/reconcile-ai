// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.rero;

import com.reconcileai.infrastructure.rero.RunTokens;
import com.reconcileai.support.AbstractReroIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Platform Q3.2: the tool budget is one conditional UPDATE, so 30 simultaneous calls against a budget of 5 yield
 * exactly 5 successes, 25 x 429 and a counter of exactly 5, with one audit row per counted call.
 */
class ReroToolBudgetConcurrencyIT extends AbstractReroIT {

    @Autowired
    RunTokens runTokens;

    @Test
    void concurrentCallsNeverExceedTheBudget() throws Exception {
        UUID job = runningJob(TENANT_A, "RERO", 5);
        String token = runTokens.mint(TENANT_A, job, Duration.ofSeconds(90));
        HttpClient http = HttpClient.newHttpClient();
        URI uri = URI.create("http://127.0.0.1:" + port + "/internal/rero/v1/jobs/" + job + "/break");
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(30)) {
            for (int i = 0; i < 30; i++) {
                results.add(pool.submit(() -> {
                    go.await();
                    return http.send(HttpRequest.newBuilder(uri).header("Authorization", "Bearer " + token).GET().build(),
                        HttpResponse.BodyHandlers.discarding()).statusCode();
                }));
            }
            go.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> f : results) {
                statuses.add(f.get());
            }
            assertThat(statuses).filteredOn(s -> s == 200).hasSize(5);
            assertThat(statuses).filteredOn(s -> s == 429).hasSize(25);
        }
        assertThat(toolCallsUsed(job)).isEqualTo(5);
        assertThat(jdbc.queryForList("SELECT seq FROM agent_tool_call WHERE job_id = ? ORDER BY seq", Integer.class, job))
            .containsExactly(1, 2, 3, 4, 5);
    }
}
