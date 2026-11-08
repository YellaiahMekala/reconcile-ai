package com.reconcileai.application.audit.anchor;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ObjectLockMode;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.net.URI;
import java.time.Instant;

/**
 * S3 (or MinIO) store that writes every anchor with an Object Lock retention date. In COMPLIANCE mode nobody, including
 * the account root, can delete or overwrite the object version until the date passes; GOVERNANCE mode lets specially
 * permitted principals bypass it. The bucket must have been created with Object Lock enabled.
 */
public class S3AnchorStore implements AnchorStore {

    private final S3Client s3;
    private final String bucket;
    private final ObjectLockMode mode;

    public S3AnchorStore(String endpoint, String region, String bucket, String accessKey, String secretKey,
                         boolean pathStyle, String lockMode, boolean createBucketIfMissing) {
        this(endpoint, region, bucket, accessKey, secretKey, pathStyle, lockMode, createBucketIfMissing, "url-connection");
    }

    /** @param httpClient {@code url-connection} (default, light) or {@code apache} (pooled, validates stale connections) */
    public S3AnchorStore(String endpoint, String region, String bucket, String accessKey, String secretKey,
                         boolean pathStyle, String lockMode, boolean createBucketIfMissing, String httpClient) {
        var b = S3Client.builder()
            .httpClient(httpClient(httpClient))
            .region(Region.of(region))
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(pathStyle).build())
            // MinIO and other S3-compatible servers predate the SDK's default trailing checksums.
            .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
            .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
            // HttpURLConnection can reuse a keep-alive socket the server already closed ("Unexpected end of file from
            // server"); a few more attempts ride that out. Safe: each anchor key is deterministic and versioned.
            .overrideConfiguration(o -> o.retryStrategy(r -> r.maxAttempts(6)));
        if (endpoint != null && !endpoint.isBlank()) {
            b.endpointOverride(URI.create(endpoint));
        }
        b.credentialsProvider(accessKey == null || accessKey.isBlank()
            ? DefaultCredentialsProvider.create()
            : StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)));
        this.s3 = b.build();
        this.bucket = bucket;
        this.mode = ObjectLockMode.fromValue(lockMode);
        if (createBucketIfMissing) {
            try {
                s3.createBucket(CreateBucketRequest.builder().bucket(bucket).objectLockEnabledForBucket(true).build());
            } catch (BucketAlreadyOwnedByYouException ignored) {
                // already there
            }
        }
    }

    private static SdkHttpClient httpClient(String kind) {
        return switch (kind == null ? "url-connection" : kind.trim().toLowerCase()) {
            case "url-connection" -> UrlConnectionHttpClient.create();
            // Apache validates a pooled connection that has sat idle before reuse, avoiding "Unexpected end of file from server".
            case "apache" -> ApacheHttpClient.builder().connectionMaxIdleTime(java.time.Duration.ofSeconds(5)).build();
            default -> throw new IllegalArgumentException("reconcile.audit.anchor.s3.http-client must be url-connection or apache, was: " + kind);
        };
    }

    @Override
    public String put(String key, byte[] body, Instant retainUntil) {
        return s3.putObject(PutObjectRequest.builder()
                .bucket(bucket).key(key).contentType("application/json")
                .objectLockMode(mode).objectLockRetainUntilDate(retainUntil).build(),
            RequestBody.fromBytes(body)).versionId();
    }

    @Override
    public byte[] get(String key, String versionId) {
        GetObjectRequest.Builder r = GetObjectRequest.builder().bucket(bucket).key(key);
        if (versionId != null) {
            r.versionId(versionId);
        }
        return s3.getObjectAsBytes(r.build()).asByteArray();
    }
}
