package com.afterduty.config;

import com.afterduty.model.User;
import com.afterduty.security.AdminCheck;
import com.afterduty.service.FirebaseAuthService;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Security configuration: Firebase auth filter + CORS.
 * No Spring Security dependency -- uses a simple servlet filter approach.
 */
@Configuration
public class SecurityConfig {

    private final FirebaseAuthService firebaseAuthService;
    private final AdminCheck adminCheck;

    // VCP-DFLT-03: the /h2-console exemption is only honored in dev mode, so a non-local
    // deployment never leaves the H2 web console reachable without authentication.
    @org.springframework.beans.factory.annotation.Value("${va-claim.auth.dev-mode:false}")
    private boolean devMode;

    public SecurityConfig(FirebaseAuthService firebaseAuthService, AdminCheck adminCheck) {
        this.firebaseAuthService = firebaseAuthService;
        this.adminCheck = adminCheck;
    }

    public static final String USER_ATTRIBUTE = "currentUser";

    /**
     * Request attribute name for the parsed {@code X-View-As} claim id.
     * Set by {@link #authFilter()} after the user is authenticated.
     * Value type: {@code Long}.
     * <ul>
     *   <li>{@code null} — header was absent; use the caller's own claim.</li>
     *   <li>{@code -1L} (sentinel) — header was present but not parseable as a positive long.</li>
     *   <li>Any positive {@code Long} — the requested claim id to view as.</li>
     * </ul>
     */
    public static final String VIEW_AS_CLAIM_ID_ATTRIBUTE = "viewAsClaimId";

    /**
     * VCP-HARD-05: baseline security response headers for the API.
     *
     * <p>There is no Spring Security dependency here, so none of its default header
     * writers run and the API was answering with no security headers at all. These are
     * the ones that make sense for a JSON API: {@code nosniff} stops a browser from
     * MIME-sniffing a response into something executable, {@code DENY} keeps any
     * response out of a frame, and HSTS pins the api subdomain to TLS. There is
     * deliberately no CSP — that belongs on the HTML-serving web app, not here.
     */
    @Bean
    public OncePerRequestFilter securityHeadersFilter() {
        return new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                            FilterChain chain) throws ServletException, IOException {
                response.setHeader("X-Content-Type-Options", "nosniff");
                response.setHeader("X-Frame-Options", "DENY");
                response.setHeader("Referrer-Policy", "no-referrer");
                // No `preload`: enrolling the domain in the browser preload list is
                // effectively irreversible and afterduty.app may still move to .com.
                response.setHeader("Strict-Transport-Security", "max-age=63072000; includeSubDomains");
                chain.doFilter(request, response);
            }
        };
    }

    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of("*"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return new CorsFilter(source);
    }

    @Bean
    public OncePerRequestFilter authFilter() {
        return new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                            FilterChain filterChain) throws ServletException, IOException {
                String path = request.getRequestURI();

                // Passwordless email-code OTP (passwordless-otp-auth-spec §7): the
                // request + verify routes are PRE-AUTH and must be exempt from the
                // auth filter. The /attach route is NOT exempt — it stays Bearer-authed.
                //
                // /request additionally does a BEST-EFFORT auth: a purpose=attach
                // request must be able to prove it is authenticated, but a missing
                // or bad token must NOT reject the call (it just falls back to the
                // signin lane — never trust a client-asserted purpose to widen scope).
                if (path.equals("/api/auth/email-code/request")) {
                    try {
                        String authorization = request.getHeader("Authorization");
                        if (authorization != null && authorization.startsWith("Bearer ")) {
                            User u = firebaseAuthService.resolveUser(authorization, null);
                            request.setAttribute(USER_ATTRIBUTE, u);
                        }
                    } catch (RuntimeException ignored) {
                        // Pre-auth route: an invalid token is fine — fall through unauthenticated.
                    }
                    filterChain.doFilter(request, response);
                    return;
                }
                if (path.equals("/api/auth/email-code/verify")) {
                    filterChain.doFilter(request, response);
                    return;
                }

                // Passkey AUTHENTICATE (auth program P1.3): the assert options +
                // verify routes are PRE-SESSION (they MINT the session) and must be
                // exempt from the auth filter — exactly like email-code verify. The
                // register/* and credentials/* routes are NOT exempt (they stay
                // Bearer-authed). assert/verify does full WebAuthn verification
                // server-side before minting, so exempting it is safe.
                if (path.equals("/api/auth/webauthn/assert/options")
                        || path.equals("/api/auth/webauthn/assert/verify")) {
                    filterChain.doFilter(request, response);
                    return;
                }

                // Device-bound token EXCHANGE (auth program P1.4 / B2 contract): the
                // exchange route is PRE-SESSION (it MINTS the session from a
                // device-held secret gated behind a fresh on-device biometric prompt)
                // and must be exempt from the auth filter — exactly like the passkey
                // assert/verify and email-code verify. The /enroll and /credentials/*
                // routes are NOT exempt (they stay Bearer-authed). /exchange does a
                // constant-time hash check against a non-revoked device_credentials
                // row server-side before minting, so exempting it is safe.
                if (path.equals("/api/auth/device/exchange")) {
                    filterChain.doFilter(request, response);
                    return;
                }

                // Dual-channel factor-2 RECOVERY (auth program P1.5 / C contract):
                // both routes are PRE-SESSION (the veteran can't sign in — that is
                // the point) and must be exempt from the auth filter. /start is
                // anti-enumeration (always 200); /verify re-proves possession with
                // a FRESH email code AND a FRESH phone ID token (both verified
                // server-side) before it mints a session or revokes any credential,
                // so exempting them is safe.
                if (path.equals("/api/auth/recovery/start")
                        || path.equals("/api/auth/recovery/verify")) {
                    filterChain.doFilter(request, response);
                    return;
                }

                // Skip auth for public endpoints. NOTE: /h2-console is dev-only — it is public
                // ONLY when dev mode is on (VCP-DFLT-03); in production it is not exempt.
                boolean h2ConsoleDevOnly = devMode && path.startsWith("/h2-console");
                if (path.equals("/") || path.equals("/health") || h2ConsoleDevOnly
                        || path.startsWith("/actuator") || path.equals("/docs")
                        || path.equals("/api/subscription/webhook")
                        || path.equals("/api/subscription/revenuecat/webhook")
                        // Public preview for invitation links — exact one-segment
                        // match so any future GET sub-route added under this
                        // prefix (e.g. /accept/{token}/messages) does not
                        // silently become public.
                        || (path.matches("/api/shares/accept/[^/]+")
                                && "GET".equalsIgnoreCase(request.getMethod()))
                        || "OPTIONS".equalsIgnoreCase(request.getMethod())) {
                    filterChain.doFilter(request, response);
                    return;
                }

                // Skip auth for non-API paths
                if (!path.startsWith("/api/")) {
                    filterChain.doFilter(request, response);
                    return;
                }

                try {
                    String authorization = request.getHeader("Authorization");
                    String devEmail = request.getHeader("X-User-Email");
                    User user = firebaseAuthService.resolveUser(authorization, devEmail);
                    request.setAttribute(USER_ATTRIBUTE, user);

                    // Admin-only: the debug pipeline + diagnostics surface (VCP-AUTHZ-01/03).
                    // Every /api/claim/debug/** route (the *DebugController classes plus the
                    // /debug/* handlers on IntakeController and EventPipelineController) drives
                    // expensive LLM work and/or exposes claim internals, so it must never be
                    // reachable by a non-admin. Enforced centrally here — before the controller —
                    // so it also covers any future debug route and stops id enumeration cold.
                    if (path.startsWith("/api/claim/debug/") && !adminCheck.isAdmin(user)) {
                        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                        response.setContentType("application/json");
                        response.getWriter().write("{\"detail\": \"admin_only\"}");
                        return;
                    }

                    // Parse X-View-As header and attach as request attribute so
                    // controllers can enforce claim-scoped access via ClaimAccessService.
                    // Controllers must NOT auto-create claims on the X-View-As path.
                    String viewAsHeader = request.getHeader("X-View-As");
                    if (viewAsHeader != null && !viewAsHeader.isBlank()) {
                        try {
                            long viewAsClaimId = Long.parseLong(viewAsHeader.trim());
                            if (viewAsClaimId <= 0) {
                                // Non-positive value is invalid; treat as malformed.
                                request.setAttribute(VIEW_AS_CLAIM_ID_ATTRIBUTE, -1L);
                            } else {
                                request.setAttribute(VIEW_AS_CLAIM_ID_ATTRIBUTE, viewAsClaimId);
                            }
                        } catch (NumberFormatException ignored) {
                            // Malformed header — store sentinel so controllers return 400.
                            request.setAttribute(VIEW_AS_CLAIM_ID_ATTRIBUTE, -1L);
                        }
                    }

                    filterChain.doFilter(request, response);
                } catch (SecurityException e) {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setContentType("application/json");
                    response.getWriter().write("{\"detail\": \"" + e.getMessage() + "\"}");
                }
            }
        };
    }
}
