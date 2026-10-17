package com.ecom.order;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ecom.audit.AuditAction;
import com.ecom.audit.AuditService;
import com.ecom.cart.Cart;
import com.ecom.cart.CartItem;
import com.ecom.cart.CartRepository;
import com.ecom.catalog.Product;
import com.ecom.catalog.ProductRepository;
import com.ecom.common.docs.DocumentNumberService;
import com.ecom.common.error.ApiException;
import com.ecom.common.error.ErrorCode;
import com.ecom.common.idempotency.IdempotencyService;
import com.ecom.common.util.IdempotencyKeys;
import com.ecom.common.web.Money;
import com.ecom.common.web.PageResponse;
import com.ecom.config.AppProperties;
import com.ecom.inventory.InventoryService;
import com.ecom.inventory.StockMovement;
import com.ecom.order.OrderDtos.CheckoutRequest;
import com.ecom.order.OrderDtos.OrderLine;
import com.ecom.order.OrderDtos.OrderResponse;
import com.ecom.order.OrderDtos.ShippingAddressDto;
import com.ecom.tax.TaxService;

/**
 * Order lifecycle with a stock-reservation model:
 * checkout RESERVES stock -> payment (finance) -> shipment CONSUMES the reservation -> delivery.
 * Cancel and expiry RELEASE it. Accounting reacts to {@link OrderEvents}; this class never calls finance.
 * Row locks always follow {@link LockOrder}: cart -> order -> products sorted by id.
 */
@Service
public class OrderService {

    public record CheckoutResult(OrderResponse order, boolean replayed) {
    }

    private static final String OP_PLACE_ORDER = "placeOrder";
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by("id"));

    private final OrderRepository orders;
    private final CartRepository carts;
    private final ProductRepository products;
    private final InventoryService inventory;
    private final TaxService taxService;
    private final IdempotencyService idempotency;
    private final AgentOrderPolicy agentPolicy;
    private final ApplicationEventPublisher events;
    private final AuditService audit;
    private final String currency;
    private final Duration paymentTimeout;
    private final int maxOpenUnpaid;

    public OrderService(OrderRepository orders, CartRepository carts, ProductRepository products,
                        InventoryService inventory, TaxService taxService, IdempotencyService idempotency,
                        AgentOrderPolicy agentPolicy, ApplicationEventPublisher events, AuditService audit,
                        AppProperties props) {
        this.orders = orders;
        this.carts = carts;
        this.products = products;
        this.inventory = inventory;
        this.taxService = taxService;
        this.idempotency = idempotency;
        this.agentPolicy = agentPolicy;
        this.events = events;
        this.audit = audit;
        this.currency = props.currency();
        this.paymentTimeout = props.orders().paymentTimeout();
        this.maxOpenUnpaid = props.orders().maxOpenUnpaidPerUser();
    }

    /**
     * One transaction: lock the cart row (R6) -> replay check -> cap check -> lock products (sorted) ->
     * re-validate availability -> snapshot server-side prices and costs -> destination tax -> verify the
     * total the buyer saw -> RESERVE stock -> remember the idempotency key -> empty the cart.
     */
    @Transactional
    public CheckoutResult checkout(UUID userId, String idempotencyKey, CheckoutRequest request) {
        IdempotencyKeys.validate(idempotencyKey);
        // R6: every checkout of this user waits here, so the replay check, the unpaid-order cap and the cart
        // contents are all read AFTER any concurrent checkout of the same user has committed.
        Cart cart = carts.lockByUserId(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.CART_EMPTY, "Cart is empty"));

        String fingerprint = idempotency.fingerprint(request);
        var replay = idempotency.replay(userId, OP_PLACE_ORDER, idempotencyKey, fingerprint);
        if (replay.isPresent()) {
            CustomerOrder existing = orders.findById(replay.get()).orElseThrow(() -> ApiException.notFound("Order"));
            return new CheckoutResult(toResponse(existing), true);
        }
        if (cart.getItems().isEmpty()) {
            throw new ApiException(ErrorCode.CART_EMPTY, "Cart is empty");
        }

        // H2: an account can't hoard stock with a pile of unpaid orders (race-free thanks to the cart lock).
        long openUnpaid = orders.countByUserIdAndStatus(userId, OrderStatus.PENDING_PAYMENT);
        if (openUnpaid >= maxOpenUnpaid) {
            throw new ApiException(ErrorCode.LIMIT_EXCEEDED,
                    "Too many unpaid orders; pay or cancel one before placing another",
                    Map.of("openUnpaidOrders", openUnpaid, "max", maxOpenUnpaid));
        }

        Map<UUID, Product> locked = products.lockAllByIds(cart.productIds()).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));

        Instant now = Instant.now();
        CustomerOrder order = new CustomerOrder(DocumentNumberService.randomNumber("ORD-"), userId, currency,
                request.shippingAddress().toEntity(), now.plus(paymentTimeout));
        for (CartItem item : cart.getItems()) {
            Product product = locked.get(item.getProductId());
            if (product == null || !product.isActive() || !product.getCurrency().equals(currency)) {
                throw new ApiException(ErrorCode.PRODUCT_UNAVAILABLE,
                        "A product in the cart is no longer available; remove it and retry",
                        Map.of("productId", item.getProductId()));
            }
            if (product.getAvailable() < item.getQuantity()) {
                throw new ApiException(ErrorCode.INSUFFICIENT_STOCK, "Not enough stock for " + product.getSku(),
                        Map.of("productId", product.getId(), "requested", item.getQuantity(),
                                "available", product.getAvailable()));
            }
            order.addItem(product.getId(), product.getSku(), product.getName(), product.getPriceMinor(),
                    product.getCostMinor(), item.getQuantity());
        }
        order.applyTax(taxService.rateBasisPointsFor(request.shippingAddress().countryCode()));

        // M1: never charge a different amount than the buyer (or their agent) agreed to.
        if (order.getTotalMinor() != request.expectedTotalMinor()) {
            throw new ApiException(ErrorCode.PRICE_CHANGED,
                    "The order total differs from expectedTotalMinor; re-quote the cart and confirm the new total",
                    Map.of("expectedTotalMinor", request.expectedTotalMinor(), "actualTotalMinor", order.getTotalMinor(),
                            "actualTotal", Money.of(order.getTotalMinor(), currency).formatted()));
        }

        // Agent guardrails (REVIEW.md §5.3): a hard cap throws here, before any stock is reserved; a soft cap
        // places the order normally but parks it for the owner's approval before it can be paid.
        AgentOrderPolicy.Decision decision = agentPolicy.evaluate(order.getTotalMinor(), currency);
        if (decision.placedByAgent()) {
            order.placedByAgent(decision.apiKeyId(), decision.needsApproval());
        }

        for (OrderItem item : order.getItems()) {
            inventory.reserve(locked.get(item.getProductId()), item.getQuantity());
        }
        orders.saveAndFlush(order); // flush so the id and generated timestamps exist

        if (order.isAwaitingApproval()) {
            audit.record(AuditAction.ORDER_AWAITING_APPROVAL, "order", order.getId(),
                    "number=" + order.getOrderNumber() + ",totalMinor=" + order.getTotalMinor()
                            + ",apiKeyId=" + decision.apiKeyId());
        } else if (order.getTotalMinor() == 0) {
            // H2: nothing to pay -> settle immediately instead of leaving it stuck in PENDING_PAYMENT.
            order.transitionTo(OrderStatus.PAID, now);
            events.publishEvent(new OrderEvents.ZeroTotalOrderSettled(order.getId()));
        }
        idempotency.remember(userId, OP_PLACE_ORDER, idempotencyKey, fingerprint, "order", order.getId());
        cart.clear();

        audit.record(AuditAction.ORDER_PLACED, "order", order.getId(),
                "number=" + order.getOrderNumber() + ",totalMinor=" + order.getTotalMinor());
        return new CheckoutResult(toResponse(order), false);
    }

    @Transactional(readOnly = true)
    public PageResponse<OrderResponse> listForUser(UUID userId, OrderStatus status, int page, int size) {
        PageRequest pageable = PageRequest.of(page, size, NEWEST_FIRST);
        Page<CustomerOrder> result = status == null
                ? orders.findByUserId(userId, pageable)
                : orders.findByUserIdAndStatus(userId, status, pageable);
        return PageResponse.of(result.map(this::toResponse));
    }

    /** Someone else's order id returns 404, never 403, so ids can't be probed. */
    @Transactional(readOnly = true)
    public OrderResponse getForUser(UUID userId, UUID orderId) {
        return orders.findByIdAndUserId(orderId, userId).map(this::toResponse)
                .orElseThrow(() -> ApiException.notFound("Order"));
    }

    @Transactional
    public OrderResponse cancelForUser(UUID userId, UUID orderId) {
        CustomerOrder order = orders.lockByIdAndUserId(orderId, userId) // R1: order row first
                .orElseThrow(() -> ApiException.notFound("Order"));
        if (!order.getStatus().customerCancellable()) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION,
                    "Only orders awaiting payment can be cancelled by the customer; request a return instead",
                    Map.of("status", order.getStatus()));
        }
        cancel(order, CancelReason.CUSTOMER, AuditAction.ORDER_CANCELLED);
        return toResponse(order);
    }

    /**
     * The owner's approval queue: orders one of their agent keys placed that are waiting on them
     * (backend/REVIEW.md §5.3).
     */
    @Transactional(readOnly = true)
    public PageResponse<OrderResponse> listAwaitingApproval(UUID userId, int page, int size) {
        PageRequest pageable = PageRequest.of(page, size, NEWEST_FIRST);
        return PageResponse.of(orders
                .findByUserIdAndApprovalStateAndStatus(userId, ApprovalState.PENDING, OrderStatus.PENDING_PAYMENT, pageable)
                .map(this::toResponse));
    }

    /**
     * The human says yes: the order becomes payable like any other. Callers must already have required an
     * interactive user session — an agent key approving its own order would defeat the entire point.
     */
    @Transactional
    public OrderResponse approveAgentOrder(UUID userId, UUID orderId) {
        CustomerOrder order = lockAwaitingApproval(userId, orderId);
        Instant now = Instant.now();
        order.decideApproval(ApprovalState.APPROVED, userId, now);
        if (order.getTotalMinor() == 0) {
            // A2: checkout's own zero-total settlement (H2) never ran for this order, because
            // isAwaitingApproval() was true at checkout time and skipped straight past it.
            order.transitionTo(OrderStatus.PAID, now);
            events.publishEvent(new OrderEvents.ZeroTotalOrderSettled(order.getId()));
        }
        audit.record(AuditAction.AGENT_ORDER_APPROVED, "order", orderId,
                "number=" + order.getOrderNumber() + ",totalMinor=" + order.getTotalMinor());
        return toResponse(order);
    }

    /**
     * The human says no: cancelled through the ordinary cancel path, so stock is released and the books stay
     * consistent exactly as they would for any other cancellation.
     */
    @Transactional
    public OrderResponse rejectAgentOrder(UUID userId, UUID orderId) {
        CustomerOrder order = lockAwaitingApproval(userId, orderId);
        order.decideApproval(ApprovalState.REJECTED, userId, Instant.now());
        cancel(order, CancelReason.CUSTOMER, AuditAction.AGENT_ORDER_REJECTED);
        return toResponse(order);
    }

    private CustomerOrder lockAwaitingApproval(UUID userId, UUID orderId) {
        CustomerOrder order = orders.lockByIdAndUserId(orderId, userId) // R1: order row first
                .orElseThrow(() -> ApiException.notFound("Order"));
        // The status check is defence in depth (A1): CustomerOrder.cancel()'s reconciliation already keeps
        // approvalState == PENDING from ever coexisting with a non-PENDING_PAYMENT status.
        if (!order.isAwaitingApproval() || order.getStatus() != OrderStatus.PENDING_PAYMENT) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, "This order is not waiting for your approval",
                    Map.of("status", order.getStatus(), "approvalState", String.valueOf(order.getApprovalState())));
        }
        return order;
    }

    /** Overdue unpaid orders, oldest first, for the expiry job (read only; each is claimed separately). */
    @Transactional(readOnly = true)
    public List<UUID> findOverdueUnpaid(int max) {
        return orders.findExpiredUnpaidIds(Instant.now(), max);
    }

    /**
     * R1: expires ONE order per transaction: claim the order row (FOR UPDATE SKIP LOCKED), then lock its
     * products sorted by id, release the reservation, cancel. Never holds more than one order's locks.
     *
     * @return false if the order was skipped (locked by a payment/another instance, or no longer overdue)
     */
    @Transactional
    public boolean expireIfOverdue(UUID orderId) {
        if (orders.claimForExpiry(orderId, Instant.now()).isEmpty()) {
            return false;
        }
        CustomerOrder order = orders.findById(orderId).orElseThrow();
        cancel(order, CancelReason.EXPIRED, AuditAction.ORDER_EXPIRED);
        return true;
    }

    /**
     * R3: a payment arrived for an EXPIRED order. The caller (finance) already holds the order row lock.
     * Re-reserves every line if the stock is still available (and the product still sold); then the order is
     * PAID again. Otherwise nothing changes and the caller refunds the payment.
     *
     * @return true if the order was reinstated as PAID
     */
    @Transactional
    public boolean reinstateAfterLatePayment(CustomerOrder order) {
        if (!order.isExpired()) {
            throw new IllegalStateException("Order " + order.getId() + " is not expired");
        }
        Map<UUID, Product> locked = lockProducts(order);
        for (OrderItem item : order.getItems()) {
            Product product = locked.get(item.getProductId());
            if (product == null || !product.isActive() || product.getAvailable() < item.getQuantity()) {
                return false;
            }
        }
        for (OrderItem item : order.getItems()) {
            inventory.reserve(locked.get(item.getProductId()), item.getQuantity());
        }
        order.reinstateAsPaid(Instant.now());
        audit.record(AuditAction.ORDER_REINSTATED_AFTER_LATE_PAYMENT, "order", order.getId(),
                "number=" + order.getOrderNumber() + ", stock re-reserved");
        return true;
    }

    @Transactional(readOnly = true)
    public PageResponse<OrderResponse> listAll(OrderStatus status, String orderNumber, int page, int size) {
        return PageResponse.of(search(status, orderNumber, page, size).map(this::toResponse));
    }

    /**
     * Staff search, newest first, shared with finance's payment list (BE-1/BE-2): optional status and optional
     * order number (exact, case-insensitive; at most one hit).
     */
    @Transactional(readOnly = true)
    public Page<CustomerOrder> search(OrderStatus status, String orderNumber, int page, int size) {
        PageRequest pageable = PageRequest.of(page, size, NEWEST_FIRST);
        String number = OrderNumbers.normalize(orderNumber);
        if (number != null) {
            return status == null ? orders.findByOrderNumber(number, pageable)
                    : orders.findByOrderNumberAndStatus(number, status, pageable);
        }
        return status == null ? orders.findAll(pageable) : orders.findByStatus(status, pageable);
    }

    @Transactional(readOnly = true)
    public OrderResponse getAny(UUID orderId) {
        return orders.findById(orderId).map(this::toResponse).orElseThrow(() -> ApiException.notFound("Order"));
    }

    /**
     * Fulfilment transitions. PAID is reachable ONLY by recording a payment (finance), so a fulfilment
     * user can never mark an unpaid order as paid.
     */
    @Transactional
    public OrderResponse updateStatus(UUID orderId, OrderStatus next) {
        if (next == OrderStatus.PAID) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION,
                    "Orders become PAID only when a payment is recorded (operation recordPayment)");
        }
        CustomerOrder order = orders.lockById(orderId).orElseThrow(() -> ApiException.notFound("Order")); // R1
        if (!order.getStatus().canTransitionTo(next)) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION,
                    "Cannot move order from " + order.getStatus() + " to " + next,
                    Map.of("status", order.getStatus(), "allowedNextStatuses", fulfilmentNext(order.getStatus())));
        }
        if (next == OrderStatus.CANCELLED) {
            cancel(order, CancelReason.STAFF, AuditAction.ORDER_CANCELLED);
            return toResponse(order);
        }
        OrderStatus from = order.getStatus();
        if (next == OrderStatus.SHIPPED) {
            Map<UUID, Product> locked = lockProducts(order);
            for (OrderItem item : order.getItems()) {
                StockMovement sale = inventory.shipReserved(locked.get(item.getProductId()), item.getQuantity(),
                        "order", order.getId(), order.getOrderNumber());
                item.recordShipmentCost(-sale.getValueMinor()); // R4: COGS = value that actually left inventory
            }
        }
        order.transitionTo(next, Instant.now());
        if (next == OrderStatus.SHIPPED) {
            events.publishEvent(new OrderEvents.OrderShipped(order.getId()));
        }
        audit.record(AuditAction.ORDER_STATUS_CHANGED, "order", order.getId(), from + "->" + next);
        return toResponse(order);
    }

    /**
     * Releases the reservation; a PAID order is refunded in full by finance (event) before it is cancelled.
     * The caller holds the order row lock (R1), so products are locked second, sorted by id.
     */
    private void cancel(CustomerOrder order, CancelReason reason, AuditAction action) {
        OrderStatus from = order.getStatus();
        if (from == OrderStatus.PAID) {
            events.publishEvent(new OrderEvents.PaidOrderCancelled(order.getId()));
        }
        Map<UUID, Product> locked = lockProducts(order);
        for (OrderItem item : order.getItems()) {
            inventory.release(locked.get(item.getProductId()), item.getQuantity());
        }
        order.cancel(reason, Instant.now());
        audit.record(action, "order", order.getId(), "from=" + from + ",reason=" + reason + ",reservation released");
    }

    private Map<UUID, Product> lockProducts(CustomerOrder order) {
        return products.lockAllByIds(order.getItems().stream().map(OrderItem::getProductId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(Product::getId, Function.identity()));
    }

    private static Set<OrderStatus> fulfilmentNext(OrderStatus status) {
        Set<OrderStatus> next = EnumSet.noneOf(OrderStatus.class);
        next.addAll(status.allowedNext());
        next.remove(OrderStatus.PAID);
        return next;
    }

    OrderResponse toResponse(CustomerOrder o) {
        List<OrderLine> lines = o.getItems().stream().map(i -> OrderLine.from(i, o.getCurrency())).toList();
        List<String> actions = new ArrayList<>();
        if (o.isAwaitingApproval()) {
            // Only the owner, in a real session, can do these — an agent key is refused at the controller.
            actions.add("approve");
            actions.add("reject");
        }
        if (o.getStatus().customerCancellable()) {
            actions.add("cancel");
        }
        if (o.getPaidAt() != null) {
            actions.add("viewInvoice");
        }
        if (o.getStatus() == OrderStatus.DELIVERED && ReturnPolicy.withinWindow(o.getDeliveredAt(), Instant.now())) {
            actions.add("requestReturn");
        }
        return new OrderResponse(o.getId(), o.getOrderNumber(), o.getStatus(), o.getCancelReason(), lines,
                Money.of(o.getSubtotalMinor(), o.getCurrency()), o.getTaxRateBasisPoints(),
                Money.of(o.getTaxMinor(), o.getCurrency()), Money.of(o.getTotalMinor(), o.getCurrency()),
                Money.of(o.getRefundedMinor(), o.getCurrency()), ShippingAddressDto.from(o.getShippingAddress()),
                actions, fulfilmentNext(o.getStatus()), o.getExpiresAt(), o.getPaidAt(), o.getShippedAt(),
                o.getDeliveredAt(), o.getCreatedAt(), o.getUpdatedAt(),
                o.getPlacedByApiKeyId(), o.getApprovalState(), o.getApprovalDecidedAt());
    }
}
