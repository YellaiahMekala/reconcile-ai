// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.infrastructure.ai.llm;

import java.net.URI;
import java.time.Duration;
import org.springframework.ai.bedrock.converse.BedrockChatOptions;
import org.springframework.ai.bedrock.converse.BedrockProxyChatModel;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.awscore.retry.AwsRetryStrategy;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClient;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClientBuilder;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClientBuilder;

/**
 * Amazon Bedrock (Converse API) as the chat model, for the "bedrock" and "bedrock-fake" profiles.
 *
 * <p>Spring AI's own auto-configuration cannot point the AWS client at another URL, so the SDK clients are built here
 * and handed to {@link BedrockProxyChatModel}. That one difference is the whole point of the two profiles:
 * <ul>
 *   <li>{@code bedrock}: real endpoint for the region, credentials from the default AWS chain (env vars, SSO profile,
 *       or the ECS task role). No keys are ever read from a file in this repo.</li>
 *   <li>{@code bedrock-fake}: {@code reconcile.bedrock.endpoint-override} points at a local WireMock server and dummy
 *       static credentials are used, so tests need no AWS account and cost nothing.</li>
 * </ul>
 *
 * <p>The AWS SDK's own retries are switched off: {@code LlmResilience} already retries transient failures and counts
 * them toward the circuit breaker, and two layers of retries would multiply both the latency and the bill.
 */
@Configuration
@Profile({"bedrock", "bedrock-fake"})
public class BedrockConfig {

    @Bean
    ChatModel bedrockChatModel(@Value("${reconcile.bedrock.region:us-east-1}") String region,
                               @Value("${reconcile.bedrock.model:amazon.nova-lite-v1:0}") String model,
                               @Value("${reconcile.bedrock.endpoint-override:}") String endpointOverride,
                               @Value("${reconcile.bedrock.static-access-key:}") String accessKey,
                               @Value("${reconcile.bedrock.static-secret-key:}") String secretKey,
                               @Value("${reconcile.bedrock.timeout-seconds:60}") long timeoutSeconds) {
        AwsCredentialsProvider credentials = accessKey.isBlank()
            ? DefaultCredentialsProvider.builder().build()
            : StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey));
        Duration timeout = Duration.ofSeconds(timeoutSeconds);
        ClientOverrideConfiguration noSdkRetries = ClientOverrideConfiguration.builder()
            .retryStrategy(AwsRetryStrategy.doNotRetry())
            .apiCallTimeout(timeout)
            .build();

        BedrockRuntimeClientBuilder sync = BedrockRuntimeClient.builder()
            .region(Region.of(region)).credentialsProvider(credentials).overrideConfiguration(noSdkRetries);
        BedrockRuntimeAsyncClientBuilder async = BedrockRuntimeAsyncClient.builder()
            .region(Region.of(region)).credentialsProvider(credentials).overrideConfiguration(noSdkRetries);
        if (!endpointOverride.isBlank()) {
            sync.endpointOverride(URI.create(endpointOverride));
            async.endpointOverride(URI.create(endpointOverride));
        }

        return BedrockProxyChatModel.builder()
            .bedrockRuntimeClient(sync.build())
            .bedrockRuntimeAsyncClient(async.build())
            .defaultOptions(BedrockChatOptions.builder().model(model).temperature(0.0).build())
            .build();
    }
}
