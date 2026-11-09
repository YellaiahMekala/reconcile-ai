package com.reconcileai.support;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Base class for every integration test: real app on a random port + real Postgres.
 *
 * <h2>Singleton container pattern (important)</h2>
 * The container is started ONCE in a static initializer and never stopped by JUnit; Ryuk
 * (Testcontainers' reaper) removes it when the JVM exits.
 *
 * <p>We intentionally do NOT use {@code @Testcontainers} + {@code @Container} here. With that
 * combination the JUnit extension stops the container after the FIRST test class, but Spring's
 * test-context cache keeps the ApplicationContext (and its DataSource pointing at the dead port)
 * for the NEXT test class - which then fails with connection refused. This bug only appears once
 * a project has two or more IT classes, which is exactly when it is most confusing.
 *
 * <h2>Why the pgvector image?</h2>
 * Same image as docker-compose, so there is no image swap later.
 * The database layer is never mocked.
 *
 * <h2>Data isolation between tests</h2>
 * The DB is shared across all IT classes in a run. Tests therefore use unique break references
 * and, where they count rows, unique tenant ids - rather than truncating tables.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"test", "mock-llm"})
public abstract class AbstractPostgresIT {

    protected static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
        DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
        .withDatabaseName("reconcile")
        .withUsername("reconcile")
        .withPassword("reconcile")
        // Every cached Spring context (one per distinct set of test properties) keeps its own Hikari pool open for the whole
        // run; the stock limit of 100 connections is used up once a dozen contexts are cached.
        .withCommand("postgres", "-c", "max_connections=400");

    static {
        POSTGRES.start();
    }

    private static JdbcTemplate admin;

    /**
     * Owner/superuser connection for test SETUP and ASSERTIONS ("what is really in the table"). It bypasses RLS on
     * purpose, like a DBA would. Production code never gets this connection.
     */
    protected static synchronized JdbcTemplate adminJdbc() {
        if (admin == null) {
            admin = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()));
        }
        return admin;
    }

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        // App runs as the restricted role created by V6 (RLS applies); Flyway runs as the container's owner.
        registry.add("spring.datasource.username", () -> "reconcile_app");
        registry.add("spring.datasource.password", () -> "reconcile_app");
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
        // Public key the resource server validates against (matches JwtTestSupport's private key).
        registry.add("spring.security.oauth2.resourceserver.jwt.public-key-location",
            () -> "classpath:jwt/dev-public.pem");
    }
}
