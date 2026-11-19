// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.infrastructure.mcp;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * reconcile.mcp.* — D1.2.2 read-only MCP server limits. The server itself is switched by
 * {@code spring.ai.mcp.server.enabled} ({@code MCP_SERVER_ENABLED}, default false).
 *
 * @param capacity        token bucket per tenant: burst of tool calls; default 30
 * @param refillPerMinute tool calls per minute per tenant; default 30
 * @param maxResultChars  characters returned by one tool call; default 4000
 * @param allowedOrigins  browser origins allowed to call /mcp; empty (default) refuses any request that carries an
 *                        Origin header. Mitigates CVE-2026-35568 (no Origin check in mcp-core 0.18.3)
 */
@ConfigurationProperties("reconcile.mcp")
public record McpProperties(Integer capacity, Integer refillPerMinute, Integer maxResultChars, List<String> allowedOrigins) {

    public McpProperties {
        capacity = capacity == null ? 30 : capacity;
        refillPerMinute = refillPerMinute == null ? 30 : refillPerMinute;
        maxResultChars = maxResultChars == null ? 4000 : maxResultChars;
        allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
    }
}
