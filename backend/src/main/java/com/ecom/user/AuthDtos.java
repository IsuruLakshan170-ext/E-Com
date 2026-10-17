package com.ecom.user;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import com.ecom.security.ActorType;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class AuthDtos {

    private AuthDtos() {
    }

    public record RegisterRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(min = 12, max = 72) String password,
            @NotBlank @Size(max = 100) String fullName) {
    }

    public record LoginRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(max = 72) String password) {
    }

    public record RefreshRequest(@NotBlank @Size(max = 200) String refreshToken) {
    }

    public record ChangePasswordRequest(
            @NotBlank @Size(max = 72) String currentPassword,
            @NotBlank @Size(min = 12, max = 72) String newPassword) {
    }

    public record TokenResponse(String accessToken, String tokenType, long expiresIn,
                                String refreshToken, long refreshExpiresIn) {
    }

    public record WhoAmIResponse(UUID userId, ActorType actorType, UUID apiKeyId, Set<String> scopes) {
    }

    public record UserResponse(UUID id, String email, String fullName, Role role, Instant createdAt) {
        static UserResponse from(User u) {
            return new UserResponse(u.getId(), u.getEmail(), u.getFullName(), u.getRole(), u.getCreatedAt());
        }
    }
}
