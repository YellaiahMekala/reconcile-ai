// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.infrastructure.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * RFC 7807 / RFC 9457 error bodies ({@code application/problem+json}), one shape for the whole API (platform D1.1.4).
 * <pre>
 * { "type": "urn:reconcile-ai:problem:break-not-found", "title": "Not Found", "status": 404,
 *   "detail": "...", "instance": "/api/v1/breaks/...",
 *   "code": "BREAK_NOT_FOUND", "message": "...", "timestamp": "..." }
 * </pre>
 * {@code code} is the stable machine-readable extension clients branch on (unchanged from the earlier error body);
 * {@code message} and {@code timestamp} are kept for existing clients. {@code detail} equals {@code message}. No stack
 * traces, SQL or other tenants' data ever go in.
 */
public final class Problems {

    private static final String TYPE_PREFIX = "urn:reconcile-ai:problem:";

    private Problems() { }

    public static URI type(String code) {
        return URI.create(TYPE_PREFIX + code.toLowerCase(Locale.ROOT).replace('_', '-'));
    }

    /** For MVC handlers. */
    public static ProblemDetail detail(HttpStatus status, String code, String message, String instance) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(status, message);
        p.setType(type(code));
        p.setTitle(status.getReasonPhrase());
        if (instance != null) {
            p.setInstance(URI.create(instance));
        }
        p.setProperty("code", code);
        p.setProperty("message", message);
        p.setProperty("timestamp", Instant.now());
        return p;
    }

    /** For servlet filters and security handlers, which run outside MVC. */
    public static void write(HttpServletResponse response, ObjectMapper json, HttpStatus status, String code,
                             String message, String instance) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", type(code).toString());
        body.put("title", status.getReasonPhrase());
        body.put("status", status.value());
        body.put("detail", message);
        if (instance != null) {
            body.put("instance", instance);
        }
        body.put("code", code);
        body.put("message", message);
        body.put("timestamp", Instant.now().toString());
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(json.writeValueAsString(body));
        response.getWriter().flush();
    }
}
