package com.reconcileai.application.audit.anchor;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;

/** Active only when {@code reconcile.audit.anchor.enabled=true}; otherwise the audit chain behaves exactly as before. */
@Configuration
@ConditionalOnProperty(prefix = "reconcile.audit.anchor", name = "enabled", havingValue = "true")
public class AuditAnchorConfig {

    @Bean
    AnchorSigner anchorSigner(@Value("${reconcile.audit.anchor.key-id:anchor-key-1}") String keyId,
                              @Value("${reconcile.audit.anchor.public-key}") String publicKey,
                              @Value("${reconcile.audit.anchor.private-key:}") String privateKey) {
        return new AnchorSigner(keyId, publicKey, privateKey);
    }

    @Bean
    AnchorStore anchorStore(@Value("${reconcile.audit.anchor.s3.endpoint:}") String endpoint,
                            @Value("${reconcile.audit.anchor.s3.region:us-east-1}") String region,
                            @Value("${reconcile.audit.anchor.s3.bucket}") String bucket,
                            @Value("${reconcile.audit.anchor.s3.access-key:}") String accessKey,
                            @Value("${reconcile.audit.anchor.s3.secret-key:}") String secretKey,
                            @Value("${reconcile.audit.anchor.s3.path-style:false}") boolean pathStyle,
                            @Value("${reconcile.audit.anchor.lock-mode:COMPLIANCE}") String lockMode,
                            @Value("${reconcile.audit.anchor.s3.create-bucket:false}") boolean createBucket) {
        return new S3AnchorStore(endpoint, region, bucket, accessKey, secretKey, pathStyle, lockMode, createBucket);
    }

    @Bean
    AuditAnchorService auditAnchorService(JdbcTemplate jdbc, AnchorStore store, AnchorSigner signer, MeterRegistry meters,
                                          @Value("${reconcile.audit.anchor.retention-days:365}") long retentionDays) {
        return new AuditAnchorService(jdbc, store, signer, Duration.ofDays(retentionDays), meters);
    }

    @Bean
    AuditAnchorJob auditAnchorJob(JdbcTemplate jdbc, AuditAnchorService service) {
        return new AuditAnchorJob(jdbc, service);
    }
}
