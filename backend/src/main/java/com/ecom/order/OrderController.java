package com.ecom.order;

import java.net.URI;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ecom.common.web.PageResponse;
import com.ecom.order.OrderDtos.CheckoutRequest;
import com.ecom.order.OrderDtos.OrderResponse;
import com.ecom.security.CurrentUser;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

@RestController
@RequestMapping("/api/v1/orders")
@Tag(name = "Orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('SCOPE_orders:write')")
    @Operation(operationId = "placeOrder",
            summary = "Check out the current cart. expectedTotalMinor must equal the total from quoteCart (else PRICE_CHANGED). "
                    + "Stock is reserved until payment; unpaid orders expire. Requires Idempotency-Key; retrying with the "
                    + "same key returns the original order (200, Idempotent-Replayed: true) instead of creating a second one.")
    public ResponseEntity<OrderResponse> placeOrder(@RequestHeader("Idempotency-Key") String idempotencyKey,
                                                    @Valid @RequestBody CheckoutRequest request) {
        OrderService.CheckoutResult result = orderService.checkout(CurrentUser.id(), idempotencyKey, request);
        if (result.replayed()) {
            return ResponseEntity.ok().header("Idempotent-Replayed", "true").body(result.order());
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .location(URI.create("/api/v1/orders/" + result.order().id()))
                .body(result.order());
    }

    @GetMapping
    @PreAuthorize("hasAuthority('SCOPE_orders:read')")
    @Operation(operationId = "listMyOrders", summary = "List the current user's orders, newest first")
    public PageResponse<OrderResponse> list(@RequestParam(required = false) OrderStatus status,
                                            @RequestParam(defaultValue = "0") @Min(0) int page,
                                            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return orderService.listForUser(CurrentUser.id(), status, page, size);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('SCOPE_orders:read')")
    @Operation(operationId = "getMyOrder", summary = "Get one of the current user's orders")
    public OrderResponse get(@PathVariable UUID id) {
        return orderService.getForUser(CurrentUser.id(), id);
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('SCOPE_orders:write')")
    @Operation(operationId = "cancelMyOrder", summary = "Cancel an order that is still awaiting payment; stock is restored")
    public OrderResponse cancel(@PathVariable UUID id) {
        return orderService.cancelForUser(CurrentUser.id(), id);
    }

    // --- human-in-the-loop approval for agent-placed orders (backend/REVIEW.md §5.3) ---
    // Deliberately NO @PreAuthorize scope: these require an interactive user session, like password changes and
    // API-key management. An agent key approving the order it just placed would defeat the whole mechanism, so
    // the check is actor type, not scope — which also keeps these three out of the MCP tool surface entirely
    // (mcp-server/src/ecom_mcp/policy.py denies anything with no published scope).

    @GetMapping("/awaiting-approval")
    @Operation(operationId = "listOrdersAwaitingApproval",
            summary = "Your orders that an agent placed above its key's threshold and that are waiting on you")
    public PageResponse<OrderResponse> awaitingApproval(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return orderService.listAwaitingApproval(CurrentUser.requireInteractiveUser().userId(), page, size);
    }

    @PostMapping("/{id}/approve")
    @Operation(operationId = "approveAgentOrder",
            summary = "Approve an order your agent placed, so it can be paid. Requires a real sign-in, not an API key.")
    public OrderResponse approve(@PathVariable UUID id) {
        return orderService.approveAgentOrder(CurrentUser.requireInteractiveUser().userId(), id);
    }

    @PostMapping("/{id}/reject")
    @Operation(operationId = "rejectAgentOrder",
            summary = "Reject an order your agent placed; it is cancelled and its stock released. "
                    + "Requires a real sign-in, not an API key.")
    public OrderResponse reject(@PathVariable UUID id) {
        return orderService.rejectAgentOrder(CurrentUser.requireInteractiveUser().userId(), id);
    }
}
