package com.ecom.security;

import java.io.IOException;
import java.util.Optional;

import org.springframework.http.HttpHeaders;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import com.ecom.apikey.ApiKeyService;
import com.ecom.audit.AuditAction;
import com.ecom.audit.AuditService;
import com.ecom.common.error.ErrorCode;
import com.ecom.common.error.Problems;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** Authenticates the X-API-Key header. Not a @Component, so it is registered only inside the security chain. */
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-API-Key";

    private final ApiKeyService apiKeyService;
    private final ProblemWriter problemWriter;
    private final AuditService audit;

    public ApiKeyAuthFilter(ApiKeyService apiKeyService, ProblemWriter problemWriter, AuditService audit) {
        this.apiKeyService = apiKeyService;
        this.problemWriter = problemWriter;
        this.audit = audit;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String rawKey = request.getHeader(HEADER);
        if (rawKey == null || rawKey.isBlank()) {
            chain.doFilter(request, response);
            return;
        }
        if (request.getHeader(HttpHeaders.AUTHORIZATION) != null) {
            problemWriter.write(response, Problems.of(ErrorCode.INVALID_TOKEN,
                    "Send either an Authorization header or an X-API-Key header, not both"));
            return;
        }

        String trimmed = rawKey.trim();
        Optional<ApiKeyAuthentication> authentication = apiKeyService.authenticate(trimmed);
        if (authentication.isEmpty()) {
            // M9: record rejected keys (display prefix only, never the secret) to spot leaked/brute-forced keys.
            String prefix = trimmed.startsWith("ek_") && trimmed.length() >= 11 ? trimmed.substring(0, 11) : "malformed";
            // R8: at most one row per client IP per minute, with the number of rejections it stands for.
            audit.recordThrottled("ip:" + request.getRemoteAddr(), AuditAction.API_KEY_REJECTED, "api_key", null,
                    "prefix=" + prefix);
            problemWriter.write(response, Problems.of(ErrorCode.INVALID_TOKEN, "API key is invalid, expired or revoked"));
            return;
        }

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication.get());
        SecurityContextHolder.setContext(context);
        try {
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
