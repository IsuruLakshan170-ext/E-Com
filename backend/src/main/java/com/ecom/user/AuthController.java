package com.ecom.user;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.ecom.security.AuthenticatedPrincipal;
import com.ecom.security.CurrentUser;
import com.ecom.user.AuthDtos.LoginRequest;
import com.ecom.user.AuthDtos.RefreshRequest;
import com.ecom.user.AuthDtos.RegisterRequest;
import com.ecom.user.AuthDtos.TokenResponse;
import com.ecom.user.AuthDtos.WhoAmIResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "register", summary = "Create a customer account and return tokens")
    public TokenResponse register(@Valid @RequestBody RegisterRequest request) {
        return authService.register(request);
    }

    @PostMapping("/login")
    @Operation(operationId = "login", summary = "Exchange email and password for an access token and refresh token")
    public TokenResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    @PostMapping("/refresh")
    @Operation(operationId = "refreshToken", summary = "Rotate a refresh token; the old one stops working")
    public TokenResponse refresh(@Valid @RequestBody RefreshRequest request) {
        return authService.refresh(request.refreshToken());
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "logout", summary = "Revoke a refresh token")
    public void logout(@Valid @RequestBody RefreshRequest request) {
        authService.logout(request.refreshToken());
    }

    @GetMapping("/whoami")
    @Operation(operationId = "whoAmI",
            summary = "Describe the caller: user id, credential type (USER or API_KEY) and granted scopes")
    public WhoAmIResponse whoAmI() {
        AuthenticatedPrincipal principal = CurrentUser.get();
        return new WhoAmIResponse(principal.userId(), principal.actorType(), principal.apiKeyId(), CurrentUser.scopes());
    }
}
