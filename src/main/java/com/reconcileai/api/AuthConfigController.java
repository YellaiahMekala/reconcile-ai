// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.api;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public, non-secret sign-in configuration for the web console: which IdP to redirect to and which public client id
 * to use. Contains no secrets (the UI is a public PKCE client); it lets one UI build work against demo keys or an IdP.
 */
@RestController
public class AuthConfigController {

    public record AuthConfig(String mode, String issuer, String clientId) {}

    private final AuthConfig config;

    public AuthConfigController(@Value("${reconcile.security.issuer-uri:}") String issuer,
                                @Value("${reconcile.security.ui-client-id:reconcile-ui}") String clientId) {
        this.config = issuer.isBlank() ? new AuthConfig("demo", null, null) : new AuthConfig("oidc", issuer, clientId);
    }

    @GetMapping("/api/v1/auth/config")
    public AuthConfig config() {
        return config;
    }
}
