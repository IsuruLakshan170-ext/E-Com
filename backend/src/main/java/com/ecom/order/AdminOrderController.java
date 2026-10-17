package com.ecom.order;

import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ecom.common.web.PageResponse;
import com.ecom.order.OrderDtos.OrderResponse;
import com.ecom.order.OrderDtos.UpdateOrderStatusRequest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;

@RestController
@RequestMapping("/api/v1/admin/orders")
@Tag(name = "Admin: orders")
@PreAuthorize("hasAuthority('SCOPE_orders:admin')")
public class AdminOrderController {

    private final OrderService orderService;

    public AdminOrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @GetMapping
    @Operation(operationId = "adminListOrders",
            summary = "List all orders, newest first, optionally filtered by status and/or exact order number "
                    + "(case-insensitive, e.g. what a customer reads out on the phone) (admin)")
    public PageResponse<OrderResponse> list(@RequestParam(required = false) OrderStatus status,
                                            @RequestParam(required = false) @Pattern(regexp = OrderNumbers.PARAM_PATTERN)
                                            String orderNumber,
                                            @RequestParam(defaultValue = "0") @Min(0) int page,
                                            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return orderService.listAll(status, orderNumber, page, size);
    }

    @GetMapping("/{id}")
    @Operation(operationId = "adminGetOrder", summary = "Get any order by id (admin)")
    public OrderResponse get(@PathVariable UUID id) {
        return orderService.getAny(id);
    }

    @PatchMapping("/{id}/status")
    @Operation(operationId = "adminUpdateOrderStatus",
            summary = "Fulfilment: move an order to SHIPPED, DELIVERED or CANCELLED (cancelling a paid order refunds it). "
                    + "PAID is set only by recordPayment.")
    public OrderResponse updateStatus(@PathVariable UUID id, @Valid @RequestBody UpdateOrderStatusRequest request) {
        return orderService.updateStatus(id, request.status());
    }
}
