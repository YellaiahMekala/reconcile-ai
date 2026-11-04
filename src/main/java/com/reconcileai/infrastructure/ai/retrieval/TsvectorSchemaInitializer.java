package com.reconcileai.infrastructure.ai.retrieval;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Adds the full-text-search column + indexes to Spring AI's vector_store table AFTER it exists.
 *
 * <h2>Why not a Flyway migration?</h2>
 * Spring AI creates vector_store (initialize-schema: true). Flyway runs BEFORE the
 * VectorStore bean initialises, so a Flyway "ALTER TABLE vector_store" would fail on a fresh database.
 * This bean takes VectorStore as a constructor dependency (so it is created after the table exists) and
 * runs in {@link #afterSingletonsInstantiated()}, which is before the web server accepts requests.
 *
 * <h2>Safety</h2>
 * All DDL is idempotent (IF NOT EXISTS) and runs under a Postgres advisory transaction lock, so several
 * app instances starting at once cannot race each other.
 */
@Component
public class TsvectorSchemaInitializer implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(TsvectorSchemaInitializer.class);
    private static final long ADVISORY_LOCK_KEY = 7_220_001L;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public TsvectorSchemaInitializer(VectorStore vectorStore, JdbcTemplate jdbc, TransactionTemplate tx) {
        // vectorStore is unused on purpose: depending on it guarantees PgVectorStore.afterPropertiesSet() ran first.
        this.jdbc = jdbc;
        this.tx = tx;
    }

    @Override
    public void afterSingletonsInstantiated() {
        tx.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT pg_advisory_xact_lock(?)", Object.class, ADVISORY_LOCK_KEY);

            // Generated column: Postgres keeps it in sync on every INSERT/UPDATE, so ingestion code does nothing extra.
            jdbc.execute("""
                ALTER TABLE vector_store
                  ADD COLUMN IF NOT EXISTS content_tsv tsvector
                  GENERATED ALWAYS AS (to_tsvector('english', coalesce(content, ''))) STORED""");
            jdbc.execute("CREATE INDEX IF NOT EXISTS idx_vector_store_content_tsv ON vector_store USING GIN (content_tsv)");

            // Expression index for the tenant/break predicates used by the keyword leg.
            jdbc.execute("""
                CREATE INDEX IF NOT EXISTS idx_vector_store_tenant_break
                  ON vector_store ((metadata->>'tenantId'), (metadata->>'breakItemId'))""");
        });
        log.info("vector_store full-text column and indexes verified");
    }
}
