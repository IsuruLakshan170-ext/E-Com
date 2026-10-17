package com.ecom.finance;

import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.ecom.finance.FinanceDtos.InvoiceResponse;
import com.ecom.security.CurrentUser;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/** Customer-facing invoice download; lives in finance so the order module doesn't depend on finance. */
@RestController
@Tag(name = "Orders")
public class CustomerInvoiceController {

    private final FinanceService financeService;

    public CustomerInvoiceController(FinanceService financeService) {
        this.financeService = financeService;
    }

    @GetMapping("/api/v1/orders/{id}/invoice")
    @PreAuthorize("hasAuthority('SCOPE_orders:read')")
    @Operation(operationId = "getMyOrderInvoice", summary = "Get the invoice of one of the current user's paid orders")
    public InvoiceResponse invoice(@PathVariable UUID id) {
        return financeService.getInvoiceForCustomer(CurrentUser.id(), id);
    }
}
