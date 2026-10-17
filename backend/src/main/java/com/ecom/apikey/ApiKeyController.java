package com.ecom.apikey;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.ecom.apikey.ApiKeyDtos.ApiKeyResponse;
import com.ecom.apikey.ApiKeyDtos.CreateApiKeyRequest;
import com.ecom.apikey.ApiKeyDtos.CreatedApiKeyResponse;
import com.ecom.audit.AuditService.AuditEventResponse;
import com.ecom.common.web.PageResponse;
import com.ecom.security.CurrentUser;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** Key management needs a real user session: an API key can never create or list keys. */
@RestController
@RequestMapping("/api/v1/api-keys")
@Tag(name = "API keys")
public class ApiKeyController {

    private final ApiKeyService apiKeyService;

    public ApiKeyController(ApiKeyService apiKeyService) {
        this.apiKeyService = apiKeyService;
    }

    @PostMapping
    @Operation(operationId = "createApiKey",
            summary = "Create a scoped API key (e.g. for the MCP server). The raw key is returned only once. "
                    + "Optional Idempotency-Key: a replay returns the key's metadata without the secret.")
    public ResponseEntity<CreatedApiKeyResponse> create(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateApiKeyRequest request) {
        ApiKeyService.CreateResult result =
                apiKeyService.create(CurrentUser.requireInteractiveUser().userId(), idempotencyKey, request);
        if (result.replayed()) {
            return ResponseEntity.ok().header("Idempotent-Replayed", "true").body(result.response());
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(result.response());
    }

    @GetMapping
    @Operation(operationId = "listApiKeys", summary = "List your API keys (metadata only, never the key itself)")
    public List<ApiKeyResponse> list() {
        return apiKeyService.list(CurrentUser.requireInteractiveUser().userId());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "revokeApiKey", summary = "Revoke one of your API keys immediately")
    public void revoke(@PathVariable UUID id) {
        apiKeyService.revoke(CurrentUser.requireInteractiveUser().userId(), id);
    }

    @GetMapping("/{id}/activity")
    @Operation(operationId = "getApiKeyActivity",
            summary = "What this agent did: every audited action taken by one of your own API keys, newest first")
    public PageResponse<AuditEventResponse> activity(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
        return apiKeyService.activity(CurrentUser.requireInteractiveUser().userId(), id, page, size);
    }
}
