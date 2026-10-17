package com.ecom.security;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import com.ecom.common.error.ErrorCode;
import com.ecom.common.error.Problems;
import com.ecom.user.TokenState;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * M7: a signature-valid JWT is accepted only if its "tv" (token version) claim still matches the
 * account and the account is enabled. Disabling, demoting, password change and "revoke all
 * credentials" bump the version, so old access tokens die within the cache TTL, not after 15 minutes.
 */
public class TokenStateFilter extends OncePerRequestFilter {

    public static final String TOKEN_VERSION_CLAIM = "tv";

    private final UserTokenStateCache cache;
    private final ProblemWriter problemWriter;

    public TokenStateFilter(UserTokenStateCache cache, ProblemWriter problemWriter) {
        this.cache = cache;
        this.problemWriter = problemWriter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwt) {
            UUID userId = UUID.fromString(jwt.getToken().getSubject());
            Object claim = jwt.getToken().getClaim(TOKEN_VERSION_CLAIM);
            long tokenVersion = claim instanceof Number n ? n.longValue() : -1;
            Optional<TokenState> state = cache.get(userId);
            if (state.isEmpty() || !state.get().enabled() || state.get().tokenVersion() != tokenVersion) {
                SecurityContextHolder.clearContext();
                response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"invalid_token\"");
                problemWriter.write(response, Problems.of(ErrorCode.INVALID_TOKEN,
                        "Session was revoked or the account changed; sign in again"));
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
