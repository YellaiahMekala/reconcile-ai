package com.reconcileai.infrastructure.mcp;

import com.reconcileai.application.audit.AuditRecorder;
import com.reconcileai.infrastructure.ai.guard.InjectionScanner;
import com.reconcileai.infrastructure.persistence.BreakItemRepository;
import com.reconcileai.infrastructure.persistence.EvidenceRepository;
import com.reconcileai.infrastructure.persistence.ResolutionProposalRepository;
import com.reconcileai.infrastructure.security.TenantContext;
import com.reconcileai.infrastructure.security.TokenBucketRateLimiter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Clock;

/**
 * D1.2.2: wires the read-only MCP tools into Spring AI's MCP server (WebMVC transport, STATELESS protocol). Present
 * only with {@code spring.ai.mcp.server.enabled=true}; the default is false, so no MCP endpoint exists.
 */
@Configuration
@ConditionalOnProperty(prefix = "spring.ai.mcp.server", name = "enabled", havingValue = "true")
public class McpServerConfig {

    public McpServerConfig(@Value("${spring.ai.mcp.server.protocol:SSE}") String protocol) {
        // SSE/STREAMABLE keep a session and may run tools off the request thread, where the caller's JWT is not
        // visible. STATELESS runs each tool on the request thread (servlet), so the tenant comes from that JWT.
        if (!"STATELESS".equalsIgnoreCase(protocol)) {
            throw new IllegalStateException("MCP server must use spring.ai.mcp.server.protocol=STATELESS (see ADR)");
        }
    }

    @Bean
    McpReadTools mcpReadTools(TenantContext tenants, BreakItemRepository breaks, EvidenceRepository evidence,
                              ResolutionProposalRepository proposals, InjectionScanner scanner, AuditRecorder audit,
                              McpProperties props, MeterRegistry meters) {
        // Own per-JVM bucket, keyed "mcp:<tenant>": MCP traffic cannot use up the investigation budget, or the reverse.
        var limiter = new TokenBucketRateLimiter(props.capacity(), props.refillPerMinute(), Clock.systemUTC());
        return new McpReadTools(tenants, breaks, evidence, proposals, scanner, audit, limiter, meters,
            props.maxResultChars());
    }

    /** The ONLY tool source for the MCP server; the app defines no other Spring AI ToolCallback beans. */
    @Bean
    ToolCallbackProvider mcpReadToolCallbacks(McpReadTools tools) {
        return MethodToolCallbackProvider.builder().toolObjects(tools).build();
    }

    /**
     * CVE-2026-35568 mitigation: mcp-core 0.18.3 does not validate Origin (DNS rebinding). A request to the MCP
     * endpoint that carries an Origin header not on {@code reconcile.mcp.allowed-origins} is refused with 403 before
     * security or the transport see it. Non-browser MCP clients send no Origin and are unaffected.
     */
    @Bean
    FilterRegistrationBean<OncePerRequestFilter> mcpOriginFilter(McpProperties props,
            @Value("${spring.ai.mcp.server.streamable-http.mcp-endpoint:/mcp}") String endpoint) {
        OncePerRequestFilter filter = new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
                    throws ServletException, IOException {
                String origin = req.getHeader("Origin");
                if (origin != null && !props.allowedOrigins().contains(origin)) {
                    // Written directly, not sendError: an ERROR dispatch would be re-authorised and become a 401.
                    res.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    res.setContentType("text/plain");
                    res.getWriter().write("origin not allowed");
                    return;
                }
                chain.doFilter(req, res);
            }
        };
        FilterRegistrationBean<OncePerRequestFilter> reg = new FilterRegistrationBean<>(filter);
        reg.addUrlPatterns(endpoint, endpoint + "/*");
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return reg;
    }
}
