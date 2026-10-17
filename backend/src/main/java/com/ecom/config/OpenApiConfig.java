package com.ecom.config;

import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeIn;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;

/**
 * Every endpoint carries a stable operationId; the MCP server maps these 1:1 to tool names,
 * so renaming one is a breaking change for MCP clients. The per-operation {@code x-required-scopes}
 * / {@code x-mcp-unscoped-ok} extensions that the MCP server's tool selection relies on are added
 * by {@link ScopeExtensionOperationCustomizer}, a separate auto-discovered springdoc customizer
 * bean (this class stays pure API metadata).
 */
@Configuration
@OpenAPIDefinition(
        info = @Info(
                title = "E-Com Backend API",
                version = "v1",
                description = "Secure e-commerce API. Errors are RFC 7807 problem+json with a stable 'code' "
                        + "and a 'retryable' flag. Authenticate with a user JWT (Authorization: Bearer) "
                        + "or a scoped API key (X-API-Key)."),
        security = {@SecurityRequirement(name = "bearerAuth"), @SecurityRequirement(name = "apiKey")})
@SecurityScheme(name = "bearerAuth", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "JWT")
@SecurityScheme(name = "apiKey", type = SecuritySchemeType.APIKEY, in = SecuritySchemeIn.HEADER, paramName = "X-API-Key")
public class OpenApiConfig {
}
