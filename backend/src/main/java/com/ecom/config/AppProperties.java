package com.ecom.config;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(String currency, Security security, Orders orders, Sod sod, Bootstrap bootstrap) {

    public record Security(Jwt jwt, Cors cors, RateLimit rateLimit, LoginThrottle loginThrottle) {
    }

    public record Jwt(String secret, String issuer, Duration accessTokenTtl, Duration refreshTokenTtl) {
    }

    public record Cors(List<String> allowedOrigins) {
    }

    /**
     * principal: per user / per API key (after authentication) - the MCP server shares one IP, so this is
     * the limit that actually protects per-agent fairness. ip: coarse pre-auth flood guard. auth: login etc.
     */
    public record RateLimit(int principalRequestsPerMinute, int ipRequestsPerMinute, int authRequestsPerMinute) {
    }

    /**
     * maxAttempts/window: failed logins per (account, client IP) before that pair is throttled.
     * accountMaxFailures/accountWindow (R5): failures per account across ALL IPs before only recently
     * trusted IPs may try; 0 disables the account ceiling.
     */
    public record LoginThrottle(int maxAttempts, Duration window, int accountMaxFailures, Duration accountWindow) {
    }

    public record Orders(Duration paymentTimeout, int maxOpenUnpaidPerUser) {
    }

    /** R10: null/missing mode means STRICT. */
    public record Sod(SodMode mode) {
    }

    public record Bootstrap(String adminEmail, String adminPassword) {
    }
}
