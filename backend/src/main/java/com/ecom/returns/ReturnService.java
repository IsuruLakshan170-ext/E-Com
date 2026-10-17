package com.ecom.returns;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ecom.audit.AuditAction;
import com.ecom.audit.AuditService;
import com.ecom.catalog.Product;
import com.ecom.catalog.ProductRepository;
import com.ecom.common.docs.DocumentNumberService;
import com.ecom.common.error.ApiException;
import com.ecom.common.error.ErrorCode;
import com.ecom.common.idempotency.IdempotencyService;
import com.ecom.common.web.Money;
import com.ecom.common.web.PageResponse;
import com.ecom.config.AppProperties;
import com.ecom.finance.FinanceService;
import com.ecom.finance.LedgerAccounts;
import com.ecom.finance.LedgerService;
import com.ecom.finance.LedgerService.Posting;
import com.ecom.finance.Refund;
import com.ecom.inventory.InventoryService;
import com.ecom.order.CustomerOrder;
import com.ecom.order.OrderItem;
import com.ecom.order.OrderRepository;
import com.ecom.order.OrderStatus;
import com.ecom.order.ReturnPolicy;
import com.ecom.returns.ReturnDtos.CreateReturnRequest;
import com.ecom.returns.ReturnDtos.ReturnLineRequest;
import com.ecom.returns.ReturnDtos.ReturnLineResponse;
import com.ecom.returns.ReturnDtos.ReturnResponse;
import com.ecom.security.CurrentUser;
import com.ecom.security.SodPolicy;

/**
 * Returns are split across three duties: customer service approves, the warehouse receives
 * (stock back + Dr Inventory / Cr COGS), finance refunds (credit note).
 * <ul>
 *   <li>Hard block in every mode: staff never decide, receive or refund their OWN return.</li>
 *   <li>Approver / receiver / refunder must differ, via {@link SodPolicy} (strict by default; small-team mode
 *       allows the same person with an audited reason, R10).</li>
 *   <li>R1 lock order: request = order; receive = return, then products; refund = order, then return.</li>
 *   <li>R4: each line restocks exactly its share of the order line's shipped cost.</li>
 * </ul>
 */
@Service
public class ReturnService {

    public record RequestResult(ReturnResponse returnRequest, boolean replayed) {
    }

    private final ReturnRequestRepository returns;
    private final OrderRepository orders;
    private final ProductRepository products;
    private final InventoryService inventory;
    private final FinanceService finance;
    private final LedgerService ledger;
    private final AuditService audit;
    private final IdempotencyService idempotency;
    private final SodPolicy sodPolicy;
    private final String currency;

    public ReturnService(ReturnRequestRepository returns, OrderRepository orders, ProductRepository products,
                         InventoryService inventory, FinanceService finance, LedgerService ledger, AuditService audit,
                         IdempotencyService idempotency, SodPolicy sodPolicy, AppProperties props) {
        this.returns = returns;
        this.orders = orders;
        this.products = products;
        this.inventory = inventory;
        this.finance = finance;
        this.ledger = ledger;
        this.audit = audit;
        this.idempotency = idempotency;
        this.sodPolicy = sodPolicy;
        this.currency = props.currency();
    }

    @Transactional
    public RequestResult request(UUID userId, UUID orderId, String idempotencyKey, CreateReturnRequest request) {
        String fingerprint = idempotency.fingerprint(new Object[] {orderId, request});
        var replay = idempotency.replay(userId, "requestReturn", idempotencyKey, fingerprint);
        if (replay.isPresent()) {
            return new RequestResult(toResponse(find(replay.get())), true);
        }
        // M3/R1: lock the order row so two simultaneous requests can't both pass the "already returned" check.
        CustomerOrder order = orders.lockByIdAndUserId(orderId, userId).orElseThrow(() -> ApiException.notFound("Order"));
        if (order.getStatus() != OrderStatus.DELIVERED) {
            throw new ApiException(ErrorCode.RETURN_NOT_ALLOWED, "Only delivered orders can be returned",
                    Map.of("status", order.getStatus()));
        }
        if (!ReturnPolicy.withinWindow(order.getDeliveredAt(), Instant.now())) {
            throw new ApiException(ErrorCode.RETURN_NOT_ALLOWED,
                    "The return window of " + ReturnPolicy.WINDOW_DAYS + " days after delivery has passed");
        }

        Map<UUID, Integer> returnedQty = new HashMap<>();
        Map<UUID, Long> returnedValue = new HashMap<>();
        for (ReturnRequest existing : returns.findByOrderIdAndStatusNot(orderId, ReturnStatus.REJECTED)) {
            for (ReturnLine l : existing.getLines()) {
                returnedQty.merge(l.getProductId(), l.getQuantity(), Integer::sum);
                returnedValue.merge(l.getProductId(), l.getRestockValueMinor(), Long::sum);
            }
        }

        ReturnRequest rma = new ReturnRequest(DocumentNumberService.randomNumber("RMA-"), orderId, userId,
                request.reason().trim());
        Set<UUID> seen = new HashSet<>();
        for (ReturnLineRequest line : request.lines()) {
            if (!seen.add(line.productId())) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "Each product may appear only once per return",
                        Map.of("productId", line.productId()));
            }
            OrderItem item = order.item(line.productId())
                    .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_FAILED, "Product is not on this order",
                            Map.of("productId", line.productId())));
            int alreadyQty = returnedQty.getOrDefault(line.productId(), 0);
            int returnable = item.getQuantity() - alreadyQty;
            if (line.quantity() > returnable) {
                throw new ApiException(ErrorCode.RETURN_NOT_ALLOWED, "Quantity exceeds what can still be returned",
                        Map.of("productId", line.productId(), "returnable", returnable));
            }
            long restock = restockValue(item, line.quantity(), alreadyQty, returnedValue.getOrDefault(line.productId(), 0L));
            rma.addLine(item.getProductId(), item.getSku(), item.getProductName(), line.quantity(),
                    item.getUnitPriceMinor(), (restock + line.quantity() / 2) / line.quantity(), restock);
        }
        returns.saveAndFlush(rma);
        idempotency.remember(userId, "requestReturn", idempotencyKey, fingerprint, "return", rma.getId());
        audit.record(AuditAction.RETURN_REQUESTED, "return", rma.getId(), "rma=" + rma.getRmaNumber());
        return new RequestResult(toResponse(rma, order.getCurrency()), false);
    }

    @Transactional(readOnly = true)
    public PageResponse<ReturnResponse> listMine(UUID userId, int page, int size) {
        return PageResponse.of(returns.findByUserId(userId, pageable(page, size)).map(this::toResponse));
    }

    @Transactional(readOnly = true)
    public ReturnResponse getMine(UUID userId, UUID id) {
        return returns.findByIdAndUserId(id, userId).map(this::toResponse).orElseThrow(() -> ApiException.notFound("Return"));
    }

    @Transactional(readOnly = true)
    public PageResponse<ReturnResponse> listAll(ReturnStatus status, int page, int size) {
        Page<ReturnRequest> result = status == null ? returns.findAll(pageable(page, size))
                : returns.findByStatus(status, pageable(page, size));
        return PageResponse.of(result.map(this::toResponse));
    }

    @Transactional(readOnly = true)
    public ReturnResponse get(UUID id) {
        return toResponse(find(id));
    }

    @Transactional
    public ReturnResponse decide(UUID id, boolean approve, String note) {
        ReturnRequest rma = lock(id);
        require(rma, ReturnStatus.REQUESTED);
        UUID actor = CurrentUser.id();
        requireNotOwnReturn(actor, rma);
        rma.decide(approve ? ReturnStatus.APPROVED : ReturnStatus.REJECTED, actor, note);
        audit.record(approve ? AuditAction.RETURN_APPROVED : AuditAction.RETURN_REJECTED, "return", id, note);
        return toResponse(rma);
    }

    /** Goods are back in the warehouse: restock at their share of the shipped cost and reverse that COGS. */
    @Transactional
    public ReturnResponse receive(UUID id, String sodOverrideReason) {
        ReturnRequest rma = lock(id); // R1: return row first, then products
        require(rma, ReturnStatus.APPROVED);
        UUID actor = CurrentUser.id();
        requireNotOwnReturn(actor, rma);
        sodPolicy.enforce(actor.equals(rma.getDecidedBy()), "receiveReturn", ErrorCode.SEGREGATION_OF_DUTIES,
                "Returned goods must be received by someone other than the person who approved the return",
                sodOverrideReason, "return", id);

        Map<UUID, Product> locked = products.lockAllByIds(
                        rma.getLines().stream().map(ReturnLine::getProductId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(Product::getId, Function.identity()));
        for (ReturnLine line : rma.getLines()) {
            inventory.receiveReturn(locked.get(line.getProductId()), line.getQuantity(), line.getRestockValueMinor(),
                    "return", rma.getId(), rma.getRmaNumber());
        }
        rma.markReceived(actor, Instant.now());
        ledger.post("Customer return received " + rma.getRmaNumber(), "customer_return", rma.getId(), List.of(
                Posting.debit(LedgerAccounts.INVENTORY, rma.costTotalMinor()),
                Posting.credit(LedgerAccounts.COST_OF_GOODS_SOLD, rma.costTotalMinor())));
        audit.record(AuditAction.RETURN_RECEIVED, "return", id, null);
        return toResponse(rma);
    }

    /** Refunds the returned merchandise plus its proportional share of the order's tax. */
    @Transactional
    public ReturnResponse refund(UUID id, String sodOverrideReason) {
        // R1: order row first, then the return row (refund changes both).
        UUID orderId = returns.findOrderIdById(id).orElseThrow(() -> ApiException.notFound("Return"));
        CustomerOrder order = orders.lockById(orderId).orElseThrow(() -> ApiException.notFound("Order"));
        ReturnRequest rma = lock(id);
        require(rma, ReturnStatus.RECEIVED);
        UUID actor = CurrentUser.id();
        requireNotOwnReturn(actor, rma);
        sodPolicy.enforce(actor.equals(rma.getDecidedBy()) || actor.equals(rma.getReceivedBy()), "refundReturn",
                ErrorCode.SEGREGATION_OF_DUTIES,
                "A return must be refunded by someone other than the person who approved or received it",
                sodOverrideReason, "return", id);

        long merchandise = rma.merchandiseTotalMinor();
        long tax = order.getSubtotalMinor() == 0 ? 0
                : (Math.multiplyExact(order.getTaxMinor(), merchandise) + order.getSubtotalMinor() / 2) / order.getSubtotalMinor();
        tax = Math.min(tax, order.getTaxMinor() - order.getRefundedTaxMinor());
        Refund refund = finance.issueRefund(order, rma.getId(), Math.addExact(merchandise, tax), tax,
                "Return " + rma.getRmaNumber());
        rma.markRefunded(refund.getId());
        audit.record(AuditAction.RETURN_REFUNDED, "return", id, "creditNote=" + refund.getCreditNoteNumber());
        return toResponse(rma);
    }

    /**
     * R4: the line's share of the shipped cost. The return that brings the returned quantity up to the full
     * line quantity takes the exact remainder, so a fully returned line restocks exactly what it shipped.
     */
    static long restockValue(OrderItem item, int quantity, int alreadyReturnedQty, long alreadyReturnedValue) {
        long shippedCost = item.getShippedCostMinor() != null
                ? item.getShippedCostMinor()
                : Math.multiplyExact(item.getUnitCostMinor(), (long) item.getQuantity()); // legacy rows
        long remaining = Math.max(0, shippedCost - alreadyReturnedValue);
        if (alreadyReturnedQty + quantity >= item.getQuantity()) {
            return remaining;
        }
        long share = (Math.multiplyExact(shippedCost, (long) quantity) + item.getQuantity() / 2) / item.getQuantity();
        return Math.min(share, remaining);
    }

    /** Conflict of interest: never relaxed, in any separation-of-duties mode (also a DB CHECK). */
    private static void requireNotOwnReturn(UUID actor, ReturnRequest rma) {
        if (actor.equals(rma.getUserId())) {
            throw new ApiException(ErrorCode.SEGREGATION_OF_DUTIES, "Staff cannot process their own return");
        }
    }

    private ReturnRequest find(UUID id) {
        return returns.findById(id).orElseThrow(() -> ApiException.notFound("Return"));
    }

    private ReturnRequest lock(UUID id) {
        return returns.lockById(id).orElseThrow(() -> ApiException.notFound("Return"));
    }

    private static void require(ReturnRequest rma, ReturnStatus expected) {
        if (rma.getStatus() != expected) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION,
                    "Return must be " + expected + " for this action", Map.of("status", rma.getStatus()));
        }
    }

    private static PageRequest pageable(int page, int size) {
        return PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by("id")));
    }

    private ReturnResponse toResponse(ReturnRequest rma) {
        return toResponse(rma, currency);
    }

    private static ReturnResponse toResponse(ReturnRequest rma, String currency) {
        List<ReturnLineResponse> lines = rma.getLines().stream()
                .map(l -> new ReturnLineResponse(l.getProductId(), l.getSku(), l.getProductName(), l.getQuantity(),
                        Money.of(l.getUnitPriceMinor(), currency), Money.of(l.lineTotalMinor(), currency)))
                .toList();
        return new ReturnResponse(rma.getId(), rma.getRmaNumber(), rma.getOrderId(), rma.getStatus(), rma.getReason(),
                rma.getResolutionNote(), lines, Money.of(rma.merchandiseTotalMinor(), currency), rma.getRefundId(),
                rma.getStatus().allowedNext(), rma.getCreatedAt(), rma.getUpdatedAt());
    }
}
