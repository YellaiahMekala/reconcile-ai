package com.reconcileai.infrastructure.rero;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Clock;
import java.util.List;
import java.util.Map;

/**
 * The security chain for {@code /internal/**} (Contract C section 2), ordered before the user chain.
 * <ul>
 *   <li>Any method other than GET is answered {@code 405} before authentication: there is no write route.</li>
 *   <li>Only a run token is accepted (ES256, our key id, iss/aud/sub/scope/exp checked, see {@link RunTokens}).
 *       A user JWT (RS256, realm key) fails signature selection and gets {@code 401 RUN_TOKEN_INVALID}.</li>
 *   <li>Path job id, job existence for the token tenant, RERO mode, RUNNING state and the budget are checked per
 *       request by the internal tool service (403 / 409 / 429).</li>
 * </ul>
 * Without configured keys (default build) every request is rejected with 401.
 */
@Configuration
public class ReroInternalSecurityConfig {

    static final String INTERNAL_PATHS = "/internal/**";

    @Bean
    RunTokens runTokens(ReroProperties properties) throws Exception {
        RunTokenKeys keys = RunTokenKeys.from(properties.runToken());
        if (properties.enabled() && !keys.present()) {
            throw new IllegalStateException("reconcile.rero.enabled=true needs reconcile.rero.run-token.private-key-pem "
                + "and public-key-pem (RERO_RUN_TOKEN_PRIVATE_KEY_PEM / RERO_RUN_TOKEN_PUBLIC_KEY_PEM)");
        }
        keys.requireMatchingPair();
        return new RunTokens(keys, Clock.systemUTC());
    }

    @Bean
    @Order(1)
    SecurityFilterChain reroInternalChain(HttpSecurity http, RunTokens runTokens, ObjectMapper json) throws Exception {
        AuthenticationEntryPoint unauthorized = (req, res, ex) ->
            write(res, json, HttpServletResponse.SC_UNAUTHORIZED, "RUN_TOKEN_INVALID", "Run token rejected");
        AccessDeniedHandler denied = (req, res, ex) ->
            write(res, json, HttpServletResponse.SC_UNAUTHORIZED, "RUN_TOKEN_INVALID", "Run token rejected");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> List.of()); // no roles: the token is a capability only
        http
            .securityMatcher(INTERNAL_PATHS)
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .addFilterBefore(new GetOnlyFilter(json), BearerTokenAuthenticationFilter.class)
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.GET, "/internal/rero/v1/jobs/*/**").authenticated()
                .anyRequest().denyAll())
            .exceptionHandling(e -> e.authenticationEntryPoint(unauthorized).accessDeniedHandler(denied))
            .oauth2ResourceServer(oauth -> oauth
                .authenticationEntryPoint(unauthorized)
                .accessDeniedHandler(denied)
                .jwt(jwt -> jwt.decoder(runTokens.decoder()).jwtAuthenticationConverter(converter)));
        return http.build();
    }

    static void write(HttpServletResponse res, ObjectMapper json, int status, String code, String message)
            throws IOException {
        if (res.isCommitted()) {
            return;
        }
        res.setStatus(status);
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        json.writeValue(res.getOutputStream(), Map.of("code", code, "message", message));
    }

    /** Read-only by construction: anything but GET is 405 before a token is even looked at. */
    static final class GetOnlyFilter extends OncePerRequestFilter {
        private final ObjectMapper json;

        GetOnlyFilter(ObjectMapper json) {
            this.json = json;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
                throws ServletException, IOException {
            if (!HttpMethod.GET.matches(req.getMethod())) {
                res.setHeader("Allow", "GET");
                write(res, json, HttpServletResponse.SC_METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED",
                    "The internal API is read-only");
                return;
            }
            chain.doFilter(req, res);
        }
    }
}
