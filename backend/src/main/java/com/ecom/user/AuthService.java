package com.ecom.user;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ecom.audit.AuditAction;
import com.ecom.audit.AuditService;
import com.ecom.common.error.ApiException;
import com.ecom.common.error.ErrorCode;
import com.ecom.common.util.Hashing;
import com.ecom.common.web.ClientIp;
import com.ecom.config.AppProperties;
import com.ecom.security.ActorType;
import com.ecom.security.TokenService;
import com.ecom.security.UserTokenStateCache;
import com.ecom.user.AuthDtos.ChangePasswordRequest;
import com.ecom.user.AuthDtos.LoginRequest;
import com.ecom.user.AuthDtos.RegisterRequest;
import com.ecom.user.AuthDtos.TokenResponse;
import com.ecom.user.AuthDtos.UserResponse;

@Service
public class AuthService {

    /** M4: a just-rotated refresh token still works this long (two tabs refreshing at once). */
    static final Duration ROTATION_GRACE = Duration.ofSeconds(30);

    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;
    private final LoginThrottle loginThrottle;
    private final UserTokenStateCache tokenStateCache;
    private final ApplicationEventPublisher events;
    private final AuditService audit;
    private final Duration refreshTtl;
    /** Used to spend the same BCrypt time when we don't check a real hash (prevents timing-based enumeration). */
    private final String dummyHash;

    public AuthService(UserRepository users, RefreshTokenRepository refreshTokens, PasswordEncoder passwordEncoder,
                       TokenService tokenService, LoginThrottle loginThrottle, UserTokenStateCache tokenStateCache,
                       ApplicationEventPublisher events, AuditService audit, AppProperties props) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.loginThrottle = loginThrottle;
        this.tokenStateCache = tokenStateCache;
        this.events = events;
        this.audit = audit;
        this.refreshTtl = props.security().jwt().refreshTokenTtl();
        this.dummyHash = passwordEncoder.encode(Hashing.randomUrlToken(16));
    }

    public static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    @Transactional
    public TokenResponse register(RegisterRequest request) {
        String email = normalizeEmail(request.email());
        PasswordPolicy.validate(request.password(), email);
        if (users.existsByEmail(email)) {
            throw new ApiException(ErrorCode.EMAIL_TAKEN, "An account with this email already exists");
        }
        User user = users.save(new User(email, passwordEncoder.encode(request.password()),
                request.fullName().trim(), Role.CUSTOMER));
        audit.recordAs(user.getId(), ActorType.USER, null, AuditAction.USER_REGISTERED, "user", user.getId(), null);
        return issueTokens(user);
    }

    /**
     * Every failure path - unknown email, wrong password, disabled account, throttled - returns the SAME
     * 401 INVALID_CREDENTIALS after roughly the same BCrypt work. noRollbackFor keeps the audit rows.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public TokenResponse login(LoginRequest request) {
        String email = normalizeEmail(request.email());
        String ip = ClientIp.current();
        Instant now = Instant.now();
        boolean fits = PasswordPolicy.fitsBcrypt(request.password());

        if (loginThrottle.isPairBlocked(email, ip, now)) {
            spendHashTime(request.password(), fits);
            audit.recordAs(null, ActorType.ANONYMOUS, null, AuditAction.LOGIN_THROTTLED, "user", null, null);
            throw invalidCredentials();
        }
        if (loginThrottle.requiresStepUp(email, ip, now)) {
            // R5: many-IP attack on this account and this IP isn't trusted. Same generic answer, no password check,
            // and no audit row per attempt (the crossing of the ceiling was audited once).
            spendHashTime(request.password(), fits);
            throw invalidCredentials();
        }

        User user = users.findByEmail(email).orElse(null);
        boolean ok;
        if (user == null || !fits) {
            spendHashTime(request.password(), fits);
            ok = false;
        } else {
            ok = passwordEncoder.matches(request.password(), user.getPasswordHash()) && user.isEnabled();
        }

        if (!ok) {
            LoginThrottle.Outcome outcome = loginThrottle.recordFailure(email, ip, now);
            AuditAction action = switch (outcome) {
                case ACCOUNT_CEILING_REACHED -> AuditAction.LOGIN_ACCOUNT_CEILING_REACHED;
                case PAIR_BLOCKED -> AuditAction.LOGIN_THROTTLED;
                case NONE -> AuditAction.LOGIN_FAILED;
            };
            audit.recordAs(user == null ? null : user.getId(), user == null ? ActorType.ANONYMOUS : ActorType.USER, null,
                    action, "user", user == null ? null : user.getId(), null);
            throw invalidCredentials();
        }

        loginThrottle.recordSuccess(email, ip, now);
        audit.recordAs(user.getId(), ActorType.USER, null, AuditAction.LOGIN_SUCCEEDED, "user", user.getId(), null);
        return issueTokens(user);
    }

    /**
     * Refresh-token rotation with reuse detection. A token rotated less than {@link #ROTATION_GRACE} ago
     * (another tab refreshing at the same moment) gets a fresh pair; any other reuse means the token was
     * stolen or replayed, so every session of that user is revoked.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public TokenResponse refresh(String rawRefreshToken) {
        RefreshToken token = refreshTokens.findByTokenHash(Hashing.sha256Hex(rawRefreshToken))
                .orElseThrow(AuthService::invalidRefreshToken);
        Instant now = Instant.now();

        if (token.getRevokedAt() != null) {
            boolean inGrace = token.getRevokeReason() == RevokeReason.ROTATED
                    && token.getRevokedAt().plus(ROTATION_GRACE).isAfter(now)
                    && token.getExpiresAt().isAfter(now);
            if (inGrace) {
                return issueTokens(enabledUser(token.getUserId()));
            }
            refreshTokens.revokeAllForUser(token.getUserId(), now, RevokeReason.REUSE_DETECTED);
            audit.recordAs(token.getUserId(), ActorType.USER, null, AuditAction.REFRESH_TOKEN_REUSED,
                    "user", token.getUserId(), "all sessions revoked");
            throw invalidRefreshToken();
        }
        if (!token.getExpiresAt().isAfter(now)) {
            throw invalidRefreshToken();
        }
        User user = enabledUser(token.getUserId());
        token.revoke(now, RevokeReason.ROTATED);
        return issueTokens(user);
    }

    @Transactional
    public void logout(String rawRefreshToken) {
        // Always 204, whether or not the token existed, to avoid acting as a token oracle.
        refreshTokens.findByTokenHash(Hashing.sha256Hex(rawRefreshToken))
                .ifPresent(t -> t.revoke(Instant.now(), RevokeReason.LOGOUT));
    }

    @Transactional(readOnly = true)
    public UserResponse profile(UUID userId) {
        return users.findById(userId).map(UserResponse::from).orElseThrow(() -> ApiException.notFound("User"));
    }

    /** H3: a password change kills EVERYTHING: refresh tokens, access tokens (token version) and API keys. */
    @Transactional
    public void changePassword(UUID userId, ChangePasswordRequest request) {
        User user = users.findById(userId).orElseThrow(() -> ApiException.notFound("User"));
        if (!PasswordPolicy.fitsBcrypt(request.currentPassword())
                || !passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw invalidCredentials();
        }
        PasswordPolicy.validate(request.newPassword(), user.getEmail());
        user.changePasswordHash(passwordEncoder.encode(request.newPassword()));
        revokeEverything(user.getId(), RevokeReason.PASSWORD_CHANGED);
        audit.record(AuditAction.PASSWORD_CHANGED, "user", userId, "all sessions and API keys revoked");
    }

    /** "Sign me out everywhere": every session, every access token and every API key. */
    @Transactional
    public void revokeOwnCredentials(UUID userId) {
        User user = users.findById(userId).orElseThrow(() -> ApiException.notFound("User"));
        user.bumpTokenVersion();
        revokeEverything(user.getId(), RevokeReason.CREDENTIALS_REVOKED);
        audit.record(AuditAction.CREDENTIALS_REVOKED, "user", userId, "self-service");
    }

    /** Public so other beans call it through the proxy unambiguously (it runs in the caller's transaction). */
    public void revokeEverything(UUID userId, RevokeReason reason) {
        refreshTokens.revokeAllForUser(userId, Instant.now(), reason);
        events.publishEvent(new CredentialsRevokedEvent(userId, reason.name()));
        tokenStateCache.evictAfterCommit(userId);
    }

    private User enabledUser(UUID userId) {
        return users.findById(userId).filter(User::isEnabled).orElseThrow(AuthService::invalidRefreshToken);
    }

    private void spendHashTime(String password, boolean fits) {
        passwordEncoder.matches(fits ? password : "x", dummyHash);
    }

    private TokenResponse issueTokens(User user) {
        TokenService.AccessToken access = tokenService.issueAccessToken(user);
        String rawRefresh = Hashing.randomUrlToken(32);
        refreshTokens.save(new RefreshToken(user.getId(), Hashing.sha256Hex(rawRefresh), Instant.now().plus(refreshTtl)));
        return new TokenResponse(access.value(), "Bearer", access.expiresInSeconds(), rawRefresh, refreshTtl.toSeconds());
    }

    private static ApiException invalidCredentials() {
        return new ApiException(ErrorCode.INVALID_CREDENTIALS, "Invalid email or password");
    }

    private static ApiException invalidRefreshToken() {
        return new ApiException(ErrorCode.INVALID_TOKEN, "Refresh token is invalid, expired or already used");
    }
}
