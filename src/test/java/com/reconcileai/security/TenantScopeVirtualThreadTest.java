package com.reconcileai.security;

import com.reconcileai.infrastructure.security.TenantScope;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TenantScope on virtual threads: the scope is per thread, is cleared when the body ends, and is never inherited by
 * another virtual thread. A plain ThreadLocal gives all three; an InheritableThreadLocal would break the third.
 */
class TenantScopeVirtualThreadTest {

    @Test
    void scopeIsVisibleOnlyInsideTheCallAndOnlyOnThatThread() throws Exception {
        try (ExecutorService vt = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<String> inside = vt.submit(() -> TenantScope.call("T-A", TenantScope::current));
            Future<String> afterOnSameKindOfThread = vt.submit(() -> {
                TenantScope.run("T-A", () -> {});
                return TenantScope.current();
            });
            Future<String> childStartedInsideScope = vt.submit(() ->
                TenantScope.call("T-A", () -> {
                    try (ExecutorService inner = Executors.newVirtualThreadPerTaskExecutor()) {
                        return inner.submit((Callable<String>) TenantScope::current).get();
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }));

            assertThat(inside.get()).isEqualTo("T-A");
            assertThat(afterOnSameKindOfThread.get()).isEmpty();
            assertThat(childStartedInsideScope.get()).as("a thread created inside a scope must not inherit it").isEmpty();
        }
    }

    @Test
    void manyInterleavedScopesNeverBleedIntoEachOther() throws Exception {
        try (ExecutorService vt = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < 500; i++) {
                String tenant = "T-" + (i % 7);
                results.add(vt.submit(() -> TenantScope.call(tenant, () -> {
                    Thread.yield();            // give the carrier away mid-scope
                    return tenant.equals(TenantScope.current()) && Thread.currentThread().isVirtual();
                })));
            }
            for (Future<Boolean> r : results) {
                assertThat(r.get()).isTrue();
            }
        }
    }
}
