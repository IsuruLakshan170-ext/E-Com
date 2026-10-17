package com.ecom.audit;

import java.time.Instant;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ecom.audit.AuditService.AuditEventResponse;
import com.ecom.audit.AuditService.AuditQuery;
import com.ecom.common.web.PageResponse;
import com.ecom.security.ActorType;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/v1/admin/audit-events")
@Tag(name = "Admin: audit")
@PreAuthorize("hasAuthority('SCOPE_audit:read')")
public class AuditController {

    private final AuditService auditService;

    public AuditController(AuditService auditService) {
        this.auditService = auditService;
    }

    @GetMapping
    @Operation(operationId = "listAuditEvents",
            summary = "Search audit events, newest first. Filters combine with AND; e.g. apiKeyId shows everything one agent key did.")
    public PageResponse<AuditEventResponse> list(
            @RequestParam(required = false) AuditAction action,
            @RequestParam(required = false) UUID actorId,
            @RequestParam(required = false) ActorType actorType,
            @RequestParam(required = false) UUID apiKeyId,
            @RequestParam(required = false) @Size(max = 50) String targetType,
            @RequestParam(required = false) @Size(max = 64) String targetId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
        return auditService.search(new AuditQuery(action, actorId, actorType, apiKeyId, targetType, targetId, from, to),
                page, size);
    }
}
