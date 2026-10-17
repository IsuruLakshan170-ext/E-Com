package com.ecom.user;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.ecom.common.util.Hashing;
import com.ecom.config.AppProperties;

/**
 * Login abuse controls. Every rejection looks exactly like a wrong password to the caller.
 * <ul>
 *   <li>H5: failures per (account, client IP): after {@code maxAttempts} in {@code window} that pair is blocked
 *       for the window. An attacker only blocks their OWN IP, never the real customer.</li>
 *   <li>R5: failures per account across ALL IPs (credential stuffing from a botnet): after
 *       {@code accountMaxFailures} in {@code accountWindow}, password checks are only allowed from IPs that
 *       logged into this account successfully in the last 30 days. Counters are keyed by the email whether or
 *       not the account exists, so nothing can be learned from them. This is the hook where a real step-up
 *       (CAPTCHA / emailed code) goes once email exists.</li>
 * </ul>
 * In-memory per instance, like the rate limiter (move to Redis when scaling out); a restart forgets trust.
 */
@Component
public class LoginThrottle {

    public enum Outcome { NONE, PAIR_BLOCKED, ACCOUNT_CEILING_REACHED }

    private static final int MAX_TRACKED = 100_000;
    private static final Duration TRUST_TTL = Duration.ofDays(30);

    private static final class Counter {
        int failures;
        Instant windowStart;
        Instant blockedUntil;
    }

    private final Map<String, Counter> pairs = new ConcurrentHashMap<>();
    private final Map<String, Counter> accounts = new ConcurrentHashMap<>();
    private final Map<String, Instant> trustedPairs = new ConcurrentHashMap<>();
    private final int maxAttempts;
    private final Duration window;
    private final int accountMaxFailures;
    private final Duration accountWindow;

    /** @Autowired: with two constructors Spring needs to be told which one to use (was a startup failure). */
    @Autowired
    public LoginThrottle(AppProperties props) {
        this(props.security().loginThrottle().maxAttempts(), props.security().loginThrottle().window(),
                props.security().loginThrottle().accountMaxFailures(),
                props.security().loginThrottle().accountWindow());
    }

    LoginThrottle(int maxAttempts, Duration window, int accountMaxFailures, Duration accountWindow) {
        this.maxAttempts = maxAttempts;
        this.window = window;
        this.accountMaxFailures = accountMaxFailures;
        this.accountWindow = accountWindow == null ? Duration.ofHours(1) : accountWindow;
    }

    /** True if this attempt must be rejected without even checking the password. */
    public boolean shouldReject(String email, String ip, Instant now) {
        return isPairBlocked(email, ip, now) || requiresStepUp(email, ip, now);
    }

    public boolean isPairBlocked(String email, String ip, Instant now) {
        Counter c = pairs.get(pairKey(email, ip));
        if (c == null) {
            return false;
        }
        synchronized (c) {
            return c.blockedUntil != null && c.blockedUntil.isAfter(now);
        }
    }

    /** R5: the account is under a many-IP attack and this IP is not one the real user recently logged in from. */
    public boolean requiresStepUp(String email, String ip, Instant now) {
        if (accountMaxFailures <= 0) {
            return false;
        }
        Counter c = accounts.get(accountKey(email));
        if (c == null) {
            return false;
        }
        boolean ceilingReached;
        synchronized (c) {
            ceilingReached = c.windowStart != null && c.windowStart.plus(accountWindow).isAfter(now)
                    && c.failures >= accountMaxFailures;
        }
        return ceilingReached && !isTrusted(email, ip, now);
    }

    /** @return what this failure triggered, if anything (the most severe when both happen at once). */
    public Outcome recordFailure(String email, String ip, Instant now) {
        evictIfLarge(now);
        boolean pairBlocked = increment(pairs, pairKey(email, ip), now, window, maxAttempts, true);
        boolean ceiling = accountMaxFailures > 0
                && increment(accounts, accountKey(email), now, accountWindow, accountMaxFailures, false);
        if (ceiling) {
            return Outcome.ACCOUNT_CEILING_REACHED;
        }
        return pairBlocked ? Outcome.PAIR_BLOCKED : Outcome.NONE;
    }

    /** A correct password from this IP: clear its pair counter and trust it for the account (not the account counter). */
    public void recordSuccess(String email, String ip, Instant now) {
        pairs.remove(pairKey(email, ip));
        trustedPairs.put(pairKey(email, ip), now);
    }

    private boolean isTrusted(String email, String ip, Instant now) {
        Instant since = trustedPairs.get(pairKey(email, ip));
        return since != null && since.plus(TRUST_TTL).isAfter(now);
    }

    /** @return true if this increment crossed the limit (once per window). */
    private static boolean increment(Map<String, Counter> map, String key, Instant now, Duration window, int limit,
                                     boolean block) {
        Counter c = map.computeIfAbsent(key, k -> new Counter());
        synchronized (c) {
            if (c.windowStart == null || !c.windowStart.plus(window).isAfter(now)) {
                c.windowStart = now;
                c.failures = 0;
                c.blockedUntil = null;
            }
            c.failures++;
            if (c.failures == limit) {
                if (block) {
                    c.blockedUntil = now.plus(window);
                }
                return true;
            }
            return false;
        }
    }

    private void evictIfLarge(Instant now) {
        if (pairs.size() > MAX_TRACKED) {
            pairs.entrySet().removeIf(e -> e.getValue().windowStart.plus(window).isBefore(now));
        }
        if (accounts.size() > MAX_TRACKED) {
            accounts.entrySet().removeIf(e -> e.getValue().windowStart.plus(accountWindow).isBefore(now));
        }
        if (trustedPairs.size() > MAX_TRACKED) {
            trustedPairs.entrySet().removeIf(e -> e.getValue().plus(TRUST_TTL).isBefore(now));
        }
    }

    // Never keep raw emails or IPs in memory dumps.
    private static String pairKey(String email, String ip) {
        return Hashing.sha256Hex(email + "|" + ip);
    }

    private static String accountKey(String email) {
        return Hashing.sha256Hex("acct|" + email);
    }
}
