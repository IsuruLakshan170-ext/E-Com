package com.ecom.security;

import java.nio.charset.StandardCharsets;
import java.util.List;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import com.ecom.config.AppProperties;
import com.nimbusds.jose.jwk.source.ImmutableSecret;

@Configuration
public class JwtConfig {

    public static final String AUDIENCE = "ecom-api";

    @Bean
    SecretKey jwtSigningKey(AppProperties props) {
        String secret = props.security().jwt().secret();
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("JWT_SECRET (app.security.jwt.secret) must be set and be at least 32 bytes");
        }
        return new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey jwtSigningKey) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(jwtSigningKey));
    }

    /** Pins the algorithm to HS256 and validates expiry, issuer and audience. */
    @Bean
    JwtDecoder jwtDecoder(SecretKey jwtSigningKey, AppProperties props) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(jwtSigningKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        OAuth2TokenValidator<Jwt> audience =
                new JwtClaimValidator<List<String>>(JwtClaimNames.AUD, aud -> aud != null && aud.contains(AUDIENCE));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(props.security().jwt().issuer()), audience));
        return decoder;
    }
}
