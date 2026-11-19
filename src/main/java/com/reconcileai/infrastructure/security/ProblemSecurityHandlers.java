// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.infrastructure.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.reconcileai.infrastructure.web.Problems;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * 401 and 403 from the security filter chain as {@code application/problem+json} (platform D1.1.4). The bearer
 * handlers run first so the RFC 6750 {@code WWW-Authenticate} header is unchanged; only a body is added. The
 * {@code detail} is generic: it never says why a token failed or whether a resource exists.
 */
final class ProblemSecurityHandlers {

    private ProblemSecurityHandlers() { }

    static AuthenticationEntryPoint entryPoint(ObjectMapper json) {
        BearerTokenAuthenticationEntryPoint bearer = new BearerTokenAuthenticationEntryPoint();
        return (request, response, ex) -> {
            bearer.commence(request, response, ex);
            if (!response.isCommitted()) {
                Problems.write(response, json, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED",
                    "A valid bearer token is required", request.getRequestURI());
            }
        };
    }

    static AccessDeniedHandler accessDenied(ObjectMapper json) {
        BearerTokenAccessDeniedHandler bearer = new BearerTokenAccessDeniedHandler();
        return (request, response, ex) -> {
            bearer.handle(request, response, ex);
            if (!response.isCommitted()) {
                Problems.write(response, json, HttpStatus.FORBIDDEN, "FORBIDDEN",
                    "The caller's role does not allow this operation", request.getRequestURI());
            }
        };
    }
}
