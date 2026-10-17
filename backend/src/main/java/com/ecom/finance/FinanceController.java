package com.ecom.finance;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.ecom.common.web.PageResponse;
import com.ecom.finance.FinanceDtos.FinanceOrderResponse;
import com.ecom.finance.FinanceDtos.InvoiceResponse;
import com.ecom.finance.FinanceDtos.JournalEntryResponse;
import com.ecom.finance.FinanceDtos.LedgerAccountResponse;
import com.ecom.finance.FinanceDtos.PaymentResponse;
import com.ecom.finance.FinanceDtos.RecordPaymentRequest;
import com.ecom.finance.FinanceDtos.RefundResponse;
import com.ecom.finance.FinanceDtos.SalesSummaryResponse;
import com.ecom.finance.FinanceDtos.TrialBalanceResponse;
import com.ecom.order.OrderNumbers;
import com.ecom.order.OrderStatus;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/v1/finance")
@Tag(name = "Finance")
public class FinanceController {

    private final FinanceService financeService;
    private final LedgerService ledgerService;
    private final FinanceOrderQueries orderQueries;

    public FinanceController(FinanceService financeService, LedgerService ledgerService,
                             FinanceOrderQueries orderQueries) {
        this.financeService = financeService;
        this.ledgerService = ledgerService;
        this.orderQueries = orderQueries;
    }

    // ---- payments, invoices, refunds ----

    @GetMapping("/orders")
    @PreAuthorize("hasAuthority('SCOPE_finance:read')")
    @Operation(operationId = "financeListOrders",
            summary = "Orders to record payments against, newest first: number, customer, total and payment deadline. "
                    + "status defaults to PENDING_PAYMENT; orderNumber (exact, case-insensitive) finds one order in "
                    + "any status, e.g. a late payment for an expired order.")
    public PageResponse<FinanceOrderResponse> orders(@RequestParam(required = false) OrderStatus status,
                                                     @RequestParam(required = false)
                                                     @Pattern(regexp = OrderNumbers.PARAM_PATTERN) String orderNumber,
                                                     @RequestParam(defaultValue = "0") @Min(0) int page,
                                                     @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
        return orderQueries.list(status, orderNumber, page, size);
    }

    @PostMapping("/orders/{orderId}/payments")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('SCOPE_finance:write')")
    @Operation(operationId = "recordPayment",
            summary = "Record full payment for an order: marks it PAID, issues the invoice and posts to the ledger. "
                    + "amountMinor must equal the order total.")
    public PaymentResponse recordPayment(@PathVariable UUID orderId, @Valid @RequestBody RecordPaymentRequest request) {
        return financeService.recordPayment(orderId, request);
    }

    @GetMapping("/payments")
    @PreAuthorize("hasAuthority('SCOPE_finance:read')")
    @Operation(operationId = "listPayments", summary = "List recorded payments, newest first")
    public PageResponse<PaymentResponse> payments(@RequestParam(defaultValue = "0") @Min(0) int page,
                                                  @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
        return financeService.listPayments(page, size);
    }

    @GetMapping("/invoices")
    @PreAuthorize("hasAuthority('SCOPE_finance:read')")
    @Operation(operationId = "listInvoices", summary = "List invoices, newest first")
    public PageResponse<InvoiceResponse> invoices(@RequestParam(defaultValue = "0") @Min(0) int page,
                                                  @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
        return financeService.listInvoices(page, size);
    }

    @GetMapping("/invoices/{id}")
    @PreAuthorize("hasAuthority('SCOPE_finance:read')")
    @Operation(operationId = "getInvoice", summary = "Get one invoice with its lines")
    public InvoiceResponse invoice(@PathVariable UUID id) {
        return financeService.getInvoice(id);
    }

    @GetMapping("/refunds")
    @PreAuthorize("hasAuthority('SCOPE_finance:read')")
    @Operation(operationId = "listRefunds", summary = "List refunds / credit notes, newest first")
    public PageResponse<RefundResponse> refunds(@RequestParam(defaultValue = "0") @Min(0) int page,
                                                @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
        return financeService.listRefunds(page, size);
    }


    // ---- ledger & reports ----

    @GetMapping("/ledger/accounts")
    @PreAuthorize("hasAuthority('SCOPE_finance:read')")
    @Operation(operationId = "listLedgerAccounts", summary = "Chart of accounts")
    public List<LedgerAccountResponse> accounts() {
        return ledgerService.listAccounts();
    }

    @GetMapping("/ledger/journal-entries")
    @PreAuthorize("hasAuthority('SCOPE_finance:read')")
    @Operation(operationId = "listJournalEntries",
            summary = "Journal entries, newest first; filter by sourceType (payment, refund, goods_receipt, order, stock_movement, customer_return)")
    public PageResponse<JournalEntryResponse> journal(@RequestParam(required = false) @Size(max = 50) String sourceType,
                                                      @RequestParam(defaultValue = "0") @Min(0) int page,
                                                      @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
        return ledgerService.listEntries(sourceType, page, size);
    }

    @GetMapping("/reports/trial-balance")
    @PreAuthorize("hasAuthority('SCOPE_finance:read')")
    @Operation(operationId = "getTrialBalance", summary = "Debit/credit totals and balance per account; 'balanced' must be true")
    public TrialBalanceResponse trialBalance() {
        return ledgerService.trialBalance();
    }

    @GetMapping("/reports/sales-summary")
    @PreAuthorize("hasAuthority('SCOPE_finance:read')")
    @Operation(operationId = "getSalesSummary", summary = "Invoiced sales, tax, refunds and net sales for a UTC date range (max 1 year)")
    public SalesSummaryResponse salesSummary(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                             @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return financeService.salesSummary(from, to);
    }
}
