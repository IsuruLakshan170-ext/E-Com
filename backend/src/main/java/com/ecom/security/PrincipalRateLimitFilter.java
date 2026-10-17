package com.ecom.security;

import java.io.IOException;
import java.util.Optional;

import org.springframework.web.filter.OncePerRequestFilter;

import com.ecom.audit.AuditService;
import com.ecom.config.AppProperties;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * H4: after authentication, every user and every API key gets its own bucket. The MCP server calls from
 * one IP on behalf of many people, so per-IP limits alone would throttle all agents together while
 * letting one abusive key hog the shared allowance.
 */
public class PrincipalRateLimitFilter extends OncePerRequestFilter {

    private final RateLimiter limiter;
    private final int perMinute;
    private final ProblemWriter problemWriter;
    private final AuditService audit;

    public PrincipalRateLimitFilter(RateLimiter limiter, AppProperties.RateLimit limits, ProblemWriter problemWriter,
                                    AuditService audit) {
        this.limiter = limiter;
        this.perMinute = limits.principalRequestsPerMinute();
        this.problemWriter = problemWriter;
        this.audit = audit;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Optional<AuthenticatedPrincipal> principal = CurrentUser.find();
        if (principal.isPresent()) {
            AuthenticatedPrincipal p = principal.get();
            String key = p.apiKeyId() != null ? "key:" + p.apiKeyId() : "user:" + p.userId();
            RateLimiter.Decision decision = limiter.tryConsume(key, perMinute);
            if (!decision.allowed()) {
                IpRateLimitFilter.reject(response, decision, key, problemWriter, audit);
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
