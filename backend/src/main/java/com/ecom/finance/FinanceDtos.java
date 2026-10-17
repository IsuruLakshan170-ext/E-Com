package com.ecom.finance;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.ecom.common.web.Money;
import com.ecom.order.CancelReason;
import com.ecom.order.OrderDtos.OrderLine;
import com.ecom.order.OrderDtos.ShippingAddressDto;
import com.ecom.order.OrderStatus;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public final class FinanceDtos {

    private FinanceDtos() {
    }

    // ---- payments / invoices / refunds ----

    /** amountMinor must equal the order total: a typo'd amount is rejected, not silently accepted. */
    public record RecordPaymentRequest(
            @NotNull PaymentMethod method,
            @NotNull @Positive Long amountMinor,
            @Size(max = 100) @Pattern(regexp = "^[A-Za-z0-9 _./:#-]*$", message = "contains invalid characters") String reference) {
    }

    /**
     * outcome tells the caller what happened (R3): APPLIED, APPLIED_AFTER_REINSTATEMENT (late payment, stock
     * re-reserved) or REFUNDED_ORDER_EXPIRED (late payment, stock gone: refundId / creditNoteNumber are set).
     */
    public record PaymentResponse(UUID id, UUID orderId, Money amount, PaymentMethod method, String reference,
                                  PaymentOutcome outcome, UUID recordedBy, Instant createdAt, UUID invoiceId,
                                  String invoiceNumber, UUID refundId, String creditNoteNumber) {
    }

    /**
     * BE-1: what finance needs to match money to an order: number, customer, amount and payment deadline (no
     * address or items; finance has no orders:admin). customerEmail/customerName are null only if the account is gone.
     */
    public record FinanceOrderResponse(UUID id, String orderNumber, OrderStatus status, CancelReason cancelReason,
                                       UUID customerId, String customerEmail, String customerName, Money total,
                                       Instant expiresAt, Instant paidAt, Instant createdAt) {
    }

    public record InvoiceResponse(UUID id, String invoiceNumber, UUID orderId, String orderNumber, Instant issuedAt,
                                  ShippingAddressDto billTo, List<OrderLine> lines, Money subtotal,
                                  int taxRateBasisPoints, Money tax, Money total, Money refunded) {
    }

    public record RefundResponse(UUID id, String creditNoteNumber, UUID orderId, UUID returnId, Money amount,
                                 Money tax, String reason, UUID createdBy, Instant createdAt) {
        static RefundResponse from(Refund r) {
            return new RefundResponse(r.getId(), r.getCreditNoteNumber(), r.getOrderId(), r.getReturnId(),
                    Money.of(r.getAmountMinor(), r.getCurrency()), Money.of(r.getTaxMinor(), r.getCurrency()),
                    r.getReason(), r.getCreatedBy(), r.getCreatedAt());
        }
    }


    // ---- ledger ----

    public record LedgerAccountResponse(String code, String name, AccountType type) {
    }

    public record JournalLineResponse(String accountCode, long debitMinor, long creditMinor) {
    }

    public record JournalEntryResponse(UUID id, String entryNumber, Instant postedAt, String description,
                                       String sourceType, UUID sourceId, UUID createdBy,
                                       List<JournalLineResponse> lines) {
    }

    public record TrialBalanceLine(String accountCode, String accountName, AccountType type, long debitMinor,
                                   long creditMinor, long balanceMinor) {
    }

    public record TrialBalanceResponse(List<TrialBalanceLine> accounts, long totalDebitMinor, long totalCreditMinor,
                                       boolean balanced) {
    }

    // ---- reports ----

    public record SalesSummaryResponse(LocalDate from, LocalDate to, long invoicesIssued, Money grossSales,
                                       Money taxInvoiced, long refundsIssued, Money refundedTotal, Money refundedTax,
                                       Money netSales) {
    }
}
