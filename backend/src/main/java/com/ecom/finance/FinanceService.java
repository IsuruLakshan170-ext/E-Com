package com.ecom.finance;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ecom.audit.AuditAction;
import com.ecom.audit.AuditService;
import com.ecom.common.docs.DocumentNumberService;
import com.ecom.common.error.ApiException;
import com.ecom.common.error.ErrorCode;
import com.ecom.common.web.Money;
import com.ecom.common.web.PageResponse;
import com.ecom.config.AppProperties;
import com.ecom.finance.FinanceDtos.InvoiceResponse;
import com.ecom.finance.FinanceDtos.PaymentResponse;
import com.ecom.finance.FinanceDtos.RecordPaymentRequest;
import com.ecom.finance.FinanceDtos.RefundResponse;
import com.ecom.finance.FinanceDtos.SalesSummaryResponse;
import com.ecom.finance.LedgerService.Posting;
import com.ecom.order.CancelReason;
import com.ecom.order.CustomerOrder;
import com.ecom.order.OrderDtos.OrderLine;
import com.ecom.order.OrderDtos.ShippingAddressDto;
import com.ecom.order.OrderRepository;
import com.ecom.order.OrderService;
import com.ecom.order.OrderStatus;
import com.ecom.security.AuthenticatedPrincipal;
import com.ecom.security.CurrentUser;

/**
 * Order-to-cash: payment -> invoice -> revenue recognised at shipment -> (credit note / refund).
 * Depends on the order module, never the other way round (order talks to finance via OrderEvents).
 */
@Service
public class FinanceService {

    private static final int MAX_REPORT_DAYS = 366;

    private final OrderRepository orders;
    private final OrderService orderService;
    private final PaymentRepository payments;
    private final InvoiceRepository invoices;
    private final RefundRepository refunds;
    private final LedgerService ledger;
    private final DocumentNumberService documentNumbers;
    private final AuditService audit;
    private final String currency;

    public FinanceService(OrderRepository orders, OrderService orderService, PaymentRepository payments,
                          InvoiceRepository invoices, RefundRepository refunds, LedgerService ledger,
                          DocumentNumberService documentNumbers, AuditService audit, AppProperties props) {
        this.orders = orders;
        this.orderService = orderService;
        this.payments = payments;
        this.invoices = invoices;
        this.refunds = refunds;
        this.ledger = ledger;
        this.documentNumbers = documentNumbers;
        this.audit = audit;
        this.currency = props.currency();
    }

    /**
     * Records a full payment. R3: the order row is locked FIRST, so the expiry job (FOR UPDATE SKIP LOCKED)
     * skips an order whose payment is being recorded, and a payment that loses the race sees the final state.
     * <ul>
     *   <li>awaiting payment -> PAID, invoice issued (APPLIED);</li>
     *   <li>already EXPIRED -> late payment: if every line can be re-reserved the order is PAID again
     *       (APPLIED_AFTER_REINSTATEMENT); otherwise the payment is recorded and immediately refunded in full
     *       (REFUNDED_ORDER_EXPIRED, audited as ORDER_EXPIRED_PAYMENT_REFUNDED);</li>
     *   <li>R11: CANCELLED by the customer or staff (or legacy, no reason) -> the payment is recorded and refunded
     *       in full (REFUNDED_ORDER_CANCELLED, audited as ORDER_CANCELLED_PAYMENT_REFUNDED); never reinstated;</li>
     *   <li>paid, shipped or delivered -> rejected (one payment per order).</li>
     * </ul>
     * M2: the money is a customer DEPOSIT until shipment: Dr Cash / Cr Customer deposits + Cr Tax payable.
     * The recorder is the logged-in finance user, or SYSTEM for a future payment webhook (L6).
     */
    @Transactional
    public PaymentResponse recordPayment(UUID orderId, RecordPaymentRequest request) {
        CustomerOrder order = orders.lockById(orderId).orElseThrow(() -> ApiException.notFound("Order"));
        UUID recorder = CurrentUser.id();
        if (recorder.equals(order.getUserId())) {
            // conflict of interest: never relaxed, in any separation-of-duties mode
            throw new ApiException(ErrorCode.SEGREGATION_OF_DUTIES, "You cannot record a payment for your own order");
        }
        if (payments.existsByOrderId(orderId)) {
            throw new ApiException(ErrorCode.CONFLICT, "A payment is already recorded for this order");
        }
        // Agent guardrails (REVIEW.md §5.3): an agent placed this above its key's threshold and the owner
        // hasn't approved it yet. Guarded by status too (A1, review round 2): a cancelled/expired order must
        // fall through to the ordinary late-payment handling below (R3/R11), not be blocked here — in the
        // normal case CustomerOrder.cancel() has already moved such an order off PENDING anyway, but this
        // keeps the two checks from ever disagreeing even if something upstream didn't.
        if (order.getStatus() == OrderStatus.PENDING_PAYMENT && order.isAwaitingApproval()) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION,
                    "This order is waiting for the customer to approve what their agent placed; it cannot be paid yet",
                    Map.of("status", order.getStatus(), "approvalState", String.valueOf(order.getApprovalState())));
        }
        if (request.amountMinor() != order.getTotalMinor()) {
            throw new ApiException(ErrorCode.AMOUNT_MISMATCH, "Payment amount must equal the order total",
                    Map.of("expectedAmountMinor", order.getTotalMinor(), "receivedAmountMinor", request.amountMinor()));
        }

        PaymentOutcome outcome = selectOutcome(order.getStatus(), order.getCancelReason(),
                () -> orderService.reinstateAfterLatePayment(order))
                .orElseThrow(() -> new ApiException(ErrorCode.INVALID_STATE_TRANSITION, "Order is not awaiting payment",
                        Map.of("status", order.getStatus())));
        if (outcome == PaymentOutcome.APPLIED) {
            order.transitionTo(OrderStatus.PAID, Instant.now());
        }

        Payment payment = payments.saveAndFlush(new Payment(orderId, request.amountMinor(), order.getCurrency(),
                request.method(), request.reference(), recorder, outcome));
        ledger.post("Payment for order " + order.getOrderNumber(), "payment", payment.getId(), List.of(
                Posting.debit(LedgerAccounts.CASH, order.getTotalMinor()),
                Posting.credit(LedgerAccounts.CUSTOMER_DEPOSITS, order.getSubtotalMinor()),
                Posting.credit(LedgerAccounts.SALES_TAX_PAYABLE, order.getTaxMinor())));
        audit.record(AuditAction.PAYMENT_RECORDED, "order", orderId,
                "method=" + request.method() + ",amountMinor=" + request.amountMinor() + ",outcome=" + outcome);

        if (outcome.refunded()) {
            boolean expired = outcome == PaymentOutcome.REFUNDED_ORDER_EXPIRED;
            Refund refund = issueRefund(order, null, order.getTotalMinor(), order.getTaxMinor(), expired
                    ? "Late payment for expired order " + order.getOrderNumber() + "; stock no longer available"
                    : "Payment received for cancelled order " + order.getOrderNumber());
            audit.record(expired ? AuditAction.ORDER_EXPIRED_PAYMENT_REFUNDED : AuditAction.ORDER_CANCELLED_PAYMENT_REFUNDED,
                    "order", orderId, "payment=" + payment.getId() + ",creditNote=" + refund.getCreditNoteNumber());
            return toResponse(payment, null, refund);
        }
        return toResponse(payment, issueInvoice(order), null);
    }

    /**
     * R3/R11: which outcome a payment gets, from the order's state alone (pure, unit-tested). {@code reinstate}
     * is called ONLY for expired orders; a customer- or staff-cancelled order is never brought back.
     *
     * @return empty if the payment must be rejected (the order is already paid, shipped or delivered)
     */
    static Optional<PaymentOutcome> selectOutcome(OrderStatus status, CancelReason cancelReason, BooleanSupplier reinstate) {
        return switch (status) {
            case PENDING_PAYMENT -> Optional.of(PaymentOutcome.APPLIED);
            case CANCELLED -> Optional.of(cancelReason == CancelReason.EXPIRED
                    ? (reinstate.getAsBoolean() ? PaymentOutcome.APPLIED_AFTER_REINSTATEMENT : PaymentOutcome.REFUNDED_ORDER_EXPIRED)
                    : PaymentOutcome.REFUNDED_ORDER_CANCELLED); // CUSTOMER, STAFF, or legacy NULL
            case PAID, SHIPPED, DELIVERED -> Optional.empty();
        };
    }

    /** Invoice for an order that was settled without payment (zero total). */
    @Transactional
    public void invoiceZeroTotalOrder(CustomerOrder order) {
        if (invoices.findByOrderId(order.getId()).isEmpty()) {
            issueInvoice(order);
        }
    }

    /**
     * Issues a refund + credit note against an order; amountMinor includes taxMinor. Before shipment the
     * money is still a deposit (Dr Customer deposits); after shipment it is a sales return (Dr Sales returns).
     */
    @Transactional
    public Refund issueRefund(CustomerOrder order, UUID returnId, long amountMinor, long taxMinor, String reason) {
        if (amountMinor <= 0 || taxMinor < 0 || taxMinor > amountMinor) {
            throw new IllegalArgumentException("Invalid refund amounts");
        }
        if (amountMinor > order.refundableMinor()) {
            throw new ApiException(ErrorCode.REFUND_EXCEEDS_BALANCE, "Refund exceeds the amount still refundable",
                    Map.of("refundableMinor", order.refundableMinor(), "requestedMinor", amountMinor));
        }
        Refund refund = refunds.saveAndFlush(new Refund(documentNumbers.next(DocumentNumberService.Sequence.CREDIT_NOTE),
                order.getId(), returnId, amountMinor, taxMinor, order.getCurrency(), reason,
                CurrentUser.find().map(AuthenticatedPrincipal::userId).orElse(null)));
        order.recordRefund(amountMinor, taxMinor);
        String netAccount = order.getShippedAt() == null ? LedgerAccounts.CUSTOMER_DEPOSITS : LedgerAccounts.SALES_RETURNS;
        ledger.post("Refund " + refund.getCreditNoteNumber() + " for order " + order.getOrderNumber(), "refund",
                refund.getId(), List.of(
                        Posting.debit(netAccount, amountMinor - taxMinor),
                        Posting.debit(LedgerAccounts.SALES_TAX_PAYABLE, taxMinor),
                        Posting.credit(LedgerAccounts.CASH, amountMinor)));
        audit.record(AuditAction.REFUND_ISSUED, "order", order.getId(),
                "creditNote=" + refund.getCreditNoteNumber() + ",amountMinor=" + amountMinor);
        return refund;
    }

    /** Refunds whatever is still refundable; nothing to do for a zero balance. */
    @Transactional
    public void refundForCancellation(CustomerOrder order) {
        if (order.refundableMinor() > 0) {
            issueRefund(order, null, order.refundableMinor(), order.getTaxMinor() - order.getRefundedTaxMinor(),
                    "Order cancelled after payment");
        }
    }

    /** Ownership check: someone else's order id returns 404. */
    @Transactional(readOnly = true)
    public InvoiceResponse getInvoiceForCustomer(UUID userId, UUID orderId) {
        CustomerOrder order = orders.findByIdAndUserId(orderId, userId).orElseThrow(() -> ApiException.notFound("Order"));
        Invoice invoice = invoices.findByOrderId(orderId)
                .orElseThrow(() -> ApiException.notFound("Invoice (the order has not been paid yet)"));
        return toResponse(invoice, order);
    }

    @Transactional(readOnly = true)
    public InvoiceResponse getInvoice(UUID invoiceId) {
        Invoice invoice = invoices.findById(invoiceId).orElseThrow(() -> ApiException.notFound("Invoice"));
        CustomerOrder order = orders.findById(invoice.getOrderId()).orElseThrow(() -> ApiException.notFound("Order"));
        return toResponse(invoice, order);
    }

    /** M11: one query for the page of invoices + one batched query for their orders (no N+1). */
    @Transactional(readOnly = true)
    public PageResponse<InvoiceResponse> listInvoices(int page, int size) {
        Page<Invoice> result = invoices.findAll(PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "issuedAt")));
        Map<UUID, CustomerOrder> byId = orders.findAllById(result.getContent().stream().map(Invoice::getOrderId).toList())
                .stream().collect(Collectors.toMap(CustomerOrder::getId, Function.identity()));
        return PageResponse.of(result.map(i -> toResponse(i, byId.get(i.getOrderId()))));
    }

    @Transactional(readOnly = true)
    public PageResponse<PaymentResponse> listPayments(int page, int size) {
        Page<Payment> result = payments.findAll(PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));
        Map<UUID, Invoice> byOrder = invoices.findByOrderIdIn(result.getContent().stream().map(Payment::getOrderId).toList())
                .stream().collect(Collectors.toMap(Invoice::getOrderId, Function.identity()));
        return PageResponse.of(result.map(p -> toResponse(p, byOrder.get(p.getOrderId()), null)));
    }

    @Transactional(readOnly = true)
    public PageResponse<RefundResponse> listRefunds(int page, int size) {
        return PageResponse.of(refunds.findAll(PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")))
                .map(RefundResponse::from));
    }

    /** Inclusive UTC date range, max one year. Net sales = invoiced subtotal - refunded (ex tax). */
    @Transactional(readOnly = true)
    public SalesSummaryResponse salesSummary(LocalDate from, LocalDate to) {
        if (from.isAfter(to) || ChronoUnit.DAYS.between(from, to) > MAX_REPORT_DAYS) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "'from' must be on or before 'to' and the range at most "
                    + MAX_REPORT_DAYS + " days");
        }
        Instant start = from.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant end = to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        AmountTotals invoiced = invoices.totalsBetween(start, end);
        AmountTotals refunded = refunds.totalsBetween(start, end);
        long refundedNet = refunded.amountMinor() - refunded.taxMinor();
        return new SalesSummaryResponse(from, to, invoiced.count(), Money.of(invoiced.amountMinor(), currency),
                Money.of(invoiced.taxMinor(), currency), refunded.count(), Money.of(refunded.amountMinor(), currency),
                Money.of(refunded.taxMinor(), currency), Money.of(invoiced.amountMinor() - refundedNet, currency));
    }

    private Invoice issueInvoice(CustomerOrder order) {
        Invoice invoice = invoices.saveAndFlush(new Invoice(documentNumbers.next(DocumentNumberService.Sequence.INVOICE),
                order.getId(), order.getUserId(), order.getCurrency(), order.getSubtotalMinor(), order.getTaxMinor(),
                order.getTotalMinor()));
        audit.record(AuditAction.INVOICE_ISSUED, "invoice", invoice.getId(), "number=" + invoice.getInvoiceNumber());
        return invoice;
    }

    private PaymentResponse toResponse(Payment p, Invoice invoice, Refund refund) {
        return new PaymentResponse(p.getId(), p.getOrderId(), Money.of(p.getAmountMinor(), p.getCurrency()),
                p.getMethod(), p.getReference(), p.getOutcome(), p.getRecordedBy(), p.getCreatedAt(),
                invoice == null ? null : invoice.getId(), invoice == null ? null : invoice.getInvoiceNumber(),
                refund == null ? null : refund.getId(), refund == null ? null : refund.getCreditNoteNumber());
    }

    private static InvoiceResponse toResponse(Invoice i, CustomerOrder o) {
        return new InvoiceResponse(i.getId(), i.getInvoiceNumber(), o.getId(), o.getOrderNumber(), i.getIssuedAt(),
                ShippingAddressDto.from(o.getShippingAddress()),
                o.getItems().stream().map(item -> OrderLine.from(item, i.getCurrency())).toList(),
                Money.of(i.getSubtotalMinor(), i.getCurrency()), o.getTaxRateBasisPoints(),
                Money.of(i.getTaxMinor(), i.getCurrency()), Money.of(i.getTotalMinor(), i.getCurrency()),
                Money.of(o.getRefundedMinor(), i.getCurrency()));
    }
}
