package com.ecom.security;

import java.io.IOException;

import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;

import com.ecom.audit.AuditAction;
import com.ecom.audit.AuditService;
import com.ecom.common.error.ErrorCode;
import com.ecom.common.error.Problems;
import com.ecom.config.AppProperties;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** Pre-authentication flood guard per client IP, with a tight bucket for POST /auth/* (login, register...). */
public class IpRateLimitFilter extends OncePerRequestFilter {

    private final RateLimiter limiter;
    private final AppProperties.RateLimit limits;
    private final ProblemWriter problemWriter;
    private final AuditService audit;

    public IpRateLimitFilter(RateLimiter limiter, AppProperties.RateLimit limits, ProblemWriter problemWriter,
                             AuditService audit) {
        this.limiter = limiter;
        this.limits = limits;
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
        boolean authEndpoint = "POST".equals(request.getMethod()) && request.getRequestURI().startsWith("/api/v1/auth/");
        String key = (authEndpoint ? "auth:" : "ip:") + request.getRemoteAddr();
        RateLimiter.Decision decision = limiter.tryConsume(key,
                authEndpoint ? limits.authRequestsPerMinute() : limits.ipRequestsPerMinute());
        if (!decision.allowed()) {
            reject(response, decision, key, problemWriter, audit);
            return;
        }
        chain.doFilter(request, response);
    }

    static void reject(HttpServletResponse response, RateLimiter.Decision decision, String key,
                       ProblemWriter problemWriter, AuditService audit) throws IOException {
        audit.recordThrottled(key, AuditAction.RATE_LIMITED, "rate_limit_bucket", null, key); // R8: <= 1 row/min per bucket
        response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(decision.retryAfterSeconds()));
        problemWriter.write(response, Problems.of(ErrorCode.RATE_LIMITED,
                "Too many requests; retry after " + decision.retryAfterSeconds() + " seconds"));
    }
}
