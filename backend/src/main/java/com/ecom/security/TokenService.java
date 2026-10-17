package com.ecom.security;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import com.ecom.config.AppProperties;
import com.ecom.user.User;

@Service
public class TokenService {

    public record AccessToken(String value, long expiresInSeconds) {
    }

    private final JwtEncoder encoder;
    private final String issuer;
    private final Duration ttl;

    public TokenService(JwtEncoder encoder, AppProperties props) {
        this.encoder = encoder;
        this.issuer = props.security().jwt().issuer();
        this.ttl = props.security().jwt().accessTokenTtl();
    }

    public AccessToken issueAccessToken(User user) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .audience(List.of(JwtConfig.AUDIENCE))
                .subject(user.getId().toString())
                .issuedAt(now)
                .expiresAt(now.plus(ttl))
                .id(UUID.randomUUID().toString())
                .claim("scope", String.join(" ", user.getRole().scopes()))
                .claim("role", user.getRole().name())
                .claim(TokenStateFilter.TOKEN_VERSION_CLAIM, user.getTokenVersion())
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new AccessToken(token, ttl.toSeconds());
    }
}
