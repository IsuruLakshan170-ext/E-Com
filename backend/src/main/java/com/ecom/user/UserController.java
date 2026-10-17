package com.ecom.user;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.ecom.security.CurrentUser;
import com.ecom.user.AuthDtos.ChangePasswordRequest;
import com.ecom.user.AuthDtos.UserResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/users/me")
@Tag(name = "Account")
public class UserController {

    private final AuthService authService;

    public UserController(AuthService authService) {
        this.authService = authService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('SCOPE_profile:read')")
    @Operation(operationId = "getMyProfile", summary = "Get the current user's profile")
    public UserResponse me() {
        return authService.profile(CurrentUser.id());
    }

    @PutMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "changeMyPassword",
            summary = "Change password and sign out all sessions (interactive user session only, not API keys)")
    public void changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        authService.changePassword(CurrentUser.requireInteractiveUser().userId(), request);
    }

    @PostMapping("/revoke-all-credentials")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "revokeAllMyCredentials",
            summary = "Sign out everywhere: revokes all sessions, access tokens and API keys (interactive session only)")
    public void revokeAll() {
        authService.revokeOwnCredentials(CurrentUser.requireInteractiveUser().userId());
    }
}
