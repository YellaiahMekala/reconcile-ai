package com.reconcileai.infrastructure.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.reconcileai.infrastructure.persistence.IdempotencyStore;
import com.reconcileai.infrastructure.persistence.IdempotencyStore.Scope;
import com.reconcileai.infrastructure.persistence.IdempotencyStore.State;
import com.reconcileai.infrastructure.persistence.IdempotencyStore.Stored;
import com.reconcileai.infrastructure.security.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Part;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * Optional {@code Idempotency-Key} on POST endpoints that create things (platform D1.1.4).
 * <pre>
 *  POST + covered path + header + authenticated JWT
 *    -> scope = (tenant from JWT, subject from JWT, "POST /concrete/path", key)
 *    -> hash  = SHA-256(method, path, query, canonical body)  canonical JSON (sorted keys, no whitespace) or
 *                                                             multipart parts (name, file name, type, SHA-256 bytes)
 *    -> INSERT ... ON CONFLICT DO NOTHING (primary key = the scope)
 *         won   -> run the request; 2xx: store status, type, Location, body; non-2xx or exception: delete the row
 *         lost  -> same hash + COMPLETED   : replay the stored response (header Idempotency-Replayed: true)
 *                  same hash + IN_PROGRESS : wait up to in-progress-wait, then replay or 409 IN_PROGRESS
 *                  other hash              : 422 IDEMPOTENCY_KEY_REUSED (problem+json)
 * </pre>
 * Without the header nothing changes. Runs after the security filter chain, so identity comes from the validated JWT
 * only, never from the request. A key is never shared across tenants, users or endpoints.
 */
@Component
public class IdempotencyFilter extends OncePerRequestFilter {

    public static final String HEADER = "Idempotency-Key";
    public static final String REPLAYED_HEADER = "Idempotency-Replayed";
    private static final int MAX_KEY_LENGTH = 128;
    private static final Logger log = LoggerFactory.getLogger(IdempotencyFilter.class);

    private final IdempotencyStore store;
    private final IdempotencyProperties props;
    private final ObjectMapper json;
    private final ObjectMapper canonical = JsonMapper.builder()
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();
    private final AntPathMatcher paths = new AntPathMatcher();

    public IdempotencyFilter(IdempotencyStore store, IdempotencyProperties props, ObjectMapper json) {
        this.store = store;
        this.props = props;
        this.json = json;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!props.enabled() || !"POST".equals(request.getMethod()) || request.getHeader(HEADER) == null) {
            return true;
        }
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return props.paths().stream().noneMatch(p -> paths.match(p, path));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!(SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken jwt)) {
            chain.doFilter(request, response); // unauthenticated: security has already answered or will
            return;
        }
        String key = request.getHeader(HEADER);
        if (!validKey(key)) {
            Problems.write(response, json, HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_INVALID",
                "Idempotency-Key must be 1 to 128 visible ASCII characters", request.getRequestURI());
            return;
        }
        String tenant = jwt.getToken().getClaimAsString(TenantContext.TENANT_CLAIM);
        String path = request.getRequestURI().substring(request.getContextPath().length());
        Scope scope = new Scope(tenant, jwt.getToken().getSubject(), "POST " + path, key);

        HttpServletRequest effective = request;
        byte[] canonicalBody;
        if (isMultipart(request)) {
            canonicalBody = multipartFingerprint(request);
        } else {
            byte[] raw = request.getInputStream().readAllBytes();
            effective = new CachedBodyRequest(request, raw);
            canonicalBody = canonicalJson(raw);
        }
        String hash = sha256(("POST\n" + path + "\n" + nullToEmpty(request.getQueryString()) + "\n")
            .getBytes(StandardCharsets.UTF_8), canonicalBody);

        long deadline = System.nanoTime() + props.inProgressWait().toNanos();
        while (!store.claim(scope, hash, props.ttl(), props.staleAfter())) {
            Optional<Stored> existing = store.find(scope);
            if (existing.isEmpty()) {
                continue; // the first request failed and released the key between our INSERT and SELECT: claim again
            }
            Stored s = existing.get();
            if (!s.requestHash().equals(hash)) {
                Problems.write(response, json, HttpStatus.UNPROCESSABLE_ENTITY, "IDEMPOTENCY_KEY_REUSED",
                    "This Idempotency-Key was already used with a different request", request.getRequestURI());
                return;
            }
            if (s.state() == State.COMPLETED) {
                replay(s, response);
                return;
            }
            if (System.nanoTime() > deadline) {
                response.setHeader("Retry-After", "1");
                Problems.write(response, json, HttpStatus.CONFLICT, "IDEMPOTENCY_REQUEST_IN_PROGRESS",
                    "A request with this Idempotency-Key is still being processed; retry shortly", request.getRequestURI());
                return;
            }
            sleep();
        }

        ContentCachingResponseWrapper wrapped = new ContentCachingResponseWrapper(response);
        boolean stored = false;
        try {
            chain.doFilter(effective, wrapped);
            int status = wrapped.getStatus();
            if (status >= 200 && status < 300 && !request.isAsyncStarted()) {
                store.complete(scope, status, wrapped.getContentType(), wrapped.getHeader("Location"),
                    wrapped.getContentAsByteArray());
                stored = true;
            }
        } finally {
            if (!stored) {
                store.release(scope);
            }
            wrapped.copyBodyToResponse();
        }
    }

    private static void replay(Stored s, HttpServletResponse response) throws IOException {
        response.setStatus(s.status());
        if (s.contentType() != null) {
            response.setContentType(s.contentType());
        }
        if (s.location() != null) {
            response.setHeader("Location", s.location());
        }
        response.setHeader(REPLAYED_HEADER, "true");
        byte[] body = s.body() == null ? new byte[0] : s.body();
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
        response.flushBuffer();
    }

    static boolean validKey(String key) {
        if (key == null || key.isEmpty() || key.length() > MAX_KEY_LENGTH) {
            return false;
        }
        return key.chars().allMatch(c -> c >= 0x21 && c <= 0x7E);
    }

    private static boolean isMultipart(HttpServletRequest request) {
        String type = request.getContentType();
        return type != null && type.toLowerCase(java.util.Locale.ROOT).startsWith(MediaType.MULTIPART_FORM_DATA_VALUE);
    }

    /** JSON bodies are compared by meaning: key order and whitespace do not make a "different" request. */
    private byte[] canonicalJson(byte[] raw) {
        if (raw.length == 0) {
            return raw;
        }
        try {
            JsonNode node = json.readTree(raw);
            Object asObject = canonical.treeToValue(node, Object.class);
            return canonical.writeValueAsBytes(asObject);
        } catch (IOException e) {
            return raw; // not JSON: the bytes are the request
        }
    }

    /** Multipart boundaries are random per client call, so parts are hashed instead of the raw body. */
    private static byte[] multipartFingerprint(HttpServletRequest request) throws IOException, ServletException {
        List<Part> parts = new ArrayList<>(request.getParts());
        parts.sort(Comparator.comparing(Part::getName).thenComparing(p -> nullToEmpty(p.getSubmittedFileName())));
        StringBuilder sb = new StringBuilder();
        for (Part p : parts) {
            byte[] bytes;
            try (InputStream in = p.getInputStream()) {
                bytes = in.readAllBytes();
            }
            sb.append(p.getName()).append('\u0000').append(nullToEmpty(p.getSubmittedFileName())).append('\u0000')
                .append(nullToEmpty(p.getContentType())).append('\u0000').append(sha256(bytes)).append('\n');
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String sha256(byte[]... chunks) {
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            for (byte[] c : chunks) {
                d.update(c);
            }
            return HexFormat.of().formatHex(d.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static void sleep() {
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.debug("interrupted while waiting for an in-progress idempotent request");
        }
    }

    /** Lets the controller read a body this filter already read for hashing. */
    private static final class CachedBodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream in = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return in.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public int read() {
                    return in.read();
                }

                @Override
                public int read(byte[] b, int off, int len) {
                    return in.read(b, off, len);
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            String enc = getCharacterEncoding() == null ? "UTF-8" : getCharacterEncoding();
            return new BufferedReader(new InputStreamReader(getInputStream(), java.nio.charset.Charset.forName(enc)));
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }
    }
}
