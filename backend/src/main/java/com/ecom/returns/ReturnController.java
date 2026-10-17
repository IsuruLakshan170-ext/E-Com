package com.ecom.returns;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.ecom.common.web.PageResponse;
import com.ecom.returns.ReturnDtos.CreateReturnRequest;
import com.ecom.returns.ReturnDtos.ReturnDecisionRequest;
import com.ecom.returns.ReturnDtos.ReturnResponse;
import com.ecom.security.CurrentUser;
import com.ecom.security.SodOverrideRequest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

@RestController
@Tag(name = "Returns")
public class ReturnController {

    private final ReturnService returnService;

    public ReturnController(ReturnService returnService) {
        this.returnService = returnService;
    }

    // ---- customer ----

    @PostMapping("/api/v1/orders/{orderId}/returns")
    @PreAuthorize("hasAuthority('SCOPE_orders:write')")
    @Operation(operationId = "requestReturn",
            summary = "Request a return for items of a delivered order (within the return window). Requires Idempotency-Key; "
                    + "a retry returns the original request (200, Idempotent-Replayed: true).")
    public ResponseEntity<ReturnResponse> request(@PathVariable UUID orderId,
                                                  @RequestHeader("Idempotency-Key") String idempotencyKey,
                                                  @Valid @RequestBody CreateReturnRequest request) {
        ReturnService.RequestResult result = returnService.request(CurrentUser.id(), orderId, idempotencyKey, request);
        if (result.replayed()) {
            return ResponseEntity.ok().header("Idempotent-Replayed", "true").body(result.returnRequest());
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(result.returnRequest());
    }

    @GetMapping("/api/v1/returns")
    @PreAuthorize("hasAuthority('SCOPE_orders:read')")
    @Operation(operationId = "listMyReturns", summary = "List the current user's returns")
    public PageResponse<ReturnResponse> listMine(@RequestParam(defaultValue = "0") @Min(0) int page,
                                                 @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return returnService.listMine(CurrentUser.id(), page, size);
    }

    @GetMapping("/api/v1/returns/{id}")
    @PreAuthorize("hasAuthority('SCOPE_orders:read')")
    @Operation(operationId = "getMyReturn", summary = "Get one of the current user's returns")
    public ReturnResponse getMine(@PathVariable UUID id) {
        return returnService.getMine(CurrentUser.id(), id);
    }

    // ---- staff ----

    @GetMapping("/api/v1/admin/returns")
    @PreAuthorize("hasAnyAuthority('SCOPE_returns:manage', 'SCOPE_inventory:write', 'SCOPE_finance:write')")
    @Operation(operationId = "adminListReturns", summary = "List returns by status (staff)")
    public PageResponse<ReturnResponse> listAll(@RequestParam(required = false) ReturnStatus status,
                                                @RequestParam(defaultValue = "0") @Min(0) int page,
                                                @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return returnService.listAll(status, page, size);
    }

    @GetMapping("/api/v1/admin/returns/{id}")
    @PreAuthorize("hasAnyAuthority('SCOPE_returns:manage', 'SCOPE_inventory:write', 'SCOPE_finance:write')")
    @Operation(operationId = "adminGetReturn", summary = "Get any return (staff)")
    public ReturnResponse get(@PathVariable UUID id) {
        return returnService.get(id);
    }

    @PostMapping("/api/v1/admin/returns/{id}/approve")
    @PreAuthorize("hasAuthority('SCOPE_returns:manage')")
    @Operation(operationId = "approveReturn", summary = "Approve a requested return (customer service)")
    public ReturnResponse approve(@PathVariable UUID id, @Valid @RequestBody(required = false) ReturnDecisionRequest request) {
        return returnService.decide(id, true, request == null ? null : request.note());
    }

    @PostMapping("/api/v1/admin/returns/{id}/reject")
    @PreAuthorize("hasAuthority('SCOPE_returns:manage')")
    @Operation(operationId = "rejectReturn", summary = "Reject a requested return with a note (customer service)")
    public ReturnResponse reject(@PathVariable UUID id, @Valid @RequestBody(required = false) ReturnDecisionRequest request) {
        return returnService.decide(id, false, request == null ? null : request.note());
    }

    @PostMapping("/api/v1/admin/returns/{id}/receive")
    @PreAuthorize("hasAuthority('SCOPE_inventory:write')")
    @Operation(operationId = "receiveReturn",
            summary = "Book returned goods back into stock (warehouse). Strict mode: not the approver. Small-team mode: "
                    + "the approver may do it with a sodOverrideReason (audited). Never the customer themselves.")
    public ReturnResponse receive(@PathVariable UUID id, @Valid @RequestBody(required = false) SodOverrideRequest body) {
        return returnService.receive(id, body == null ? null : body.sodOverrideReason());
    }

    @PostMapping("/api/v1/admin/returns/{id}/refund")
    @PreAuthorize("hasAuthority('SCOPE_finance:write')")
    @Operation(operationId = "refundReturn",
            summary = "Refund a received return and issue a credit note (finance). Strict mode: not the approver or "
                    + "receiver. Small-team mode: allowed with a sodOverrideReason (audited). Never the customer themselves.")
    public ReturnResponse refund(@PathVariable UUID id, @Valid @RequestBody(required = false) SodOverrideRequest body) {
        return returnService.refund(id, body == null ? null : body.sodOverrideReason());
    }
}
