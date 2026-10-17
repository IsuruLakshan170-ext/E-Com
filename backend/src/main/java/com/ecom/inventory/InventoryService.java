package com.ecom.inventory;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ecom.audit.AuditAction;
import com.ecom.audit.AuditService;
import com.ecom.catalog.Product;
import com.ecom.catalog.ProductCreatedEvent;
import com.ecom.catalog.ProductLabel;
import com.ecom.catalog.ProductRepository;
import com.ecom.catalog.StockTotals;
import com.ecom.common.error.ApiException;
import com.ecom.common.error.ErrorCode;
import com.ecom.common.idempotency.IdempotencyService;
import com.ecom.common.util.IdempotencyKeys;
import com.ecom.common.web.Money;
import com.ecom.common.web.PageResponse;
import com.ecom.config.AppProperties;
import com.ecom.inventory.InventoryDtos.InventoryItemResponse;
import com.ecom.inventory.InventoryDtos.InventoryValuationResponse;
import com.ecom.inventory.InventoryDtos.StockAdjustmentRequest;
import com.ecom.inventory.InventoryDtos.StockMovementResponse;
import com.ecom.security.AuthenticatedPrincipal;
import com.ecom.security.CurrentUser;

/**
 * The ONLY place stock changes.
 * <ul>
 *   <li>on-hand changes write an immutable StockMovement row carrying the exact VALUE moved (R4), which is
 *       also the amount posted to GL 1200, so GL Inventory == sum of product stock values at all times;</li>
 *   <li>reservations (checkout -> ship/cancel/expiry) change only the reserved counter;</li>
 *   <li>value changes outside purchases/sales are announced as {@link StockValuationEvent} for finance.</li>
 * </ul>
 * Callers hold the product row locks (ProductRepository#lockAllByIds, sorted by id). Depends only on catalog.
 */
@Service
public class InventoryService {

    public record AdjustResult(InventoryItemResponse item, boolean replayed) {
    }

    private static final String OP_ADJUST = "adjustStock";

    private final ProductRepository products;
    private final StockMovementRepository movements;
    private final IdempotencyService idempotency;
    private final ApplicationEventPublisher events;
    private final AuditService audit;
    private final String currency;

    public InventoryService(ProductRepository products, StockMovementRepository movements, IdempotencyService idempotency,
                            ApplicationEventPublisher events, AuditService audit, AppProperties props) {
        this.products = products;
        this.movements = movements;
        this.idempotency = idempotency;
        this.events = events;
        this.audit = audit;
        this.currency = props.currency();
    }

    // ---------- reservations ----------

    public void reserve(Product product, int quantity) {
        if (quantity > product.getAvailable()) {
            throw new ApiException(ErrorCode.INSUFFICIENT_STOCK, "Not enough stock for " + product.getSku(),
                    Map.of("productId", product.getId(), "requested", quantity, "available", product.getAvailable()));
        }
        product.reserve(quantity);
    }

    public void release(Product product, int quantity) {
        product.releaseReservation(quantity);
    }

    /**
     * Reserved units leave the warehouse. R4: the movement's value is the cost of goods sold, taken from the
     * value pool AT SHIPMENT; callers store it on the order line and finance posts exactly that amount.
     */
    @Transactional
    public StockMovement shipReserved(Product product, int quantity, String referenceType, UUID referenceId, String note) {
        long value = product.consumeReservation(quantity);
        return save(product, MovementType.SALE, -quantity, unitCostOf(value, quantity), -value, referenceType, referenceId, note);
    }

    /** R4: a customer return comes back at exactly its share of the shipped cost (not today's average). */
    @Transactional
    public StockMovement receiveReturn(Product product, int quantity, long valueMinor, String referenceType,
                                       UUID referenceId, String note) {
        long value = product.receiveValue(quantity, valueMinor);
        return save(product, MovementType.CUSTOMER_RETURN, quantity, unitCostOf(value, quantity), value,
                referenceType, referenceId, note);
    }

    // ---------- other on-hand movements ----------

    /**
     * Inbound/outbound on-hand movement (purchase receipts, adjustments, opening stock).
     *
     * @param unitCostMinor cost of inbound units for movements that revalue cost; ignored otherwise
     */
    @Transactional
    public StockMovement move(Product product, MovementType type, int quantityDelta, Long unitCostMinor,
                              String referenceType, UUID referenceId, String note) {
        if (quantityDelta == 0) {
            throw new IllegalArgumentException("quantityDelta must not be zero");
        }
        if (quantityDelta > 0) {
            long value = type.revaluesCost() && unitCostMinor != null
                    ? product.receiveAtCost(quantityDelta, unitCostMinor)
                    : product.increaseStock(quantityDelta);
            return save(product, type, quantityDelta, unitCostOf(value, quantityDelta), value, referenceType, referenceId, note);
        }
        long value = product.decreaseStock(-quantityDelta);
        return save(product, type, quantityDelta, unitCostOf(value, -quantityDelta), -value, referenceType, referenceId, note);
    }

    /** Opening balance of a new product (catalog publishes the event in its creating transaction). */
    @EventListener
    @Transactional
    public void onProductCreated(ProductCreatedEvent event) {
        if (event.initialStock() <= 0) {
            return;
        }
        Product product = products.findById(event.productId()).orElseThrow();
        StockMovement m = move(product, MovementType.OPENING, event.initialStock(), event.unitCostMinor(),
                "product", product.getId(), "Opening stock");
        events.publishEvent(new StockValuationEvent(m.getId(), StockValuationEvent.Kind.OPENING,
                "Opening stock " + product.getSku(), m.getValueMinor()));
    }

    /**
     * Stock count corrections, damage, shrinkage. Can never remove units already promised to orders.
     * R7: requires an Idempotency-Key, so a retried (agent) call can never adjust twice. The product row is
     * locked BEFORE the replay check, so a concurrent duplicate waits and then replays instead of failing.
     */
    @Transactional
    public AdjustResult adjust(UUID productId, String idempotencyKey, StockAdjustmentRequest request) {
        IdempotencyKeys.validate(idempotencyKey);
        UUID actor = CurrentUser.id();
        Product product = products.lockAllByIds(List.of(productId)).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("Product"));
        String fingerprint = idempotency.fingerprint(new Object[] {productId, request});
        if (idempotency.replay(actor, OP_ADJUST, idempotencyKey, fingerprint).isPresent()) {
            return new AdjustResult(toItem(product), true);
        }

        int delta = request.delta();
        if (delta == 0) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "delta must not be zero");
        }
        if (delta < 0 && -delta > product.getAvailable()) {
            throw new ApiException(ErrorCode.INSUFFICIENT_STOCK,
                    "Adjustment would remove stock that is reserved for orders or make stock negative",
                    Map.of("onHand", product.getStock(), "reserved", product.getReserved(),
                            "available", product.getAvailable(), "delta", delta));
        }
        StockMovement m = move(product, MovementType.ADJUSTMENT, delta, null, null, null, request.reason());
        events.publishEvent(new StockValuationEvent(m.getId(),
                delta > 0 ? StockValuationEvent.Kind.ADJUSTMENT_GAIN : StockValuationEvent.Kind.ADJUSTMENT_LOSS,
                "Stock adjustment " + product.getSku() + ": " + request.reason(), Math.abs(m.getValueMinor())));
        idempotency.remember(actor, OP_ADJUST, idempotencyKey, fingerprint, "stock_movement", m.getId());
        audit.record(AuditAction.STOCK_ADJUSTED, "product", productId, "delta=" + delta + ",reason=" + request.reason());
        return new AdjustResult(toItem(product), false);
    }

    // ---------- reads ----------

    @Transactional(readOnly = true)
    public InventoryItemResponse getItem(UUID productId) {
        return products.findById(productId).map(this::toItem).orElseThrow(() -> ApiException.notFound("Product"));
    }

    @Transactional(readOnly = true)
    public PageResponse<StockMovementResponse> listMovements(UUID productId, MovementType type, int page, int size) {
        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by("id")));
        Page<StockMovement> result;
        if (productId != null && type != null) {
            result = movements.findByProductIdAndType(productId, type, pageable);
        } else if (productId != null) {
            result = movements.findByProductId(productId, pageable);
        } else if (type != null) {
            result = movements.findByType(type, pageable);
        } else {
            result = movements.findAll(pageable);
        }
        Map<UUID, ProductLabel> labels = products.labelsById(
                result.getContent().stream().map(StockMovement::getProductId).toList());
        return PageResponse.of(result.map(m -> {
            ProductLabel product = labels.getOrDefault(m.getProductId(), ProductLabel.missing(m.getProductId()));
            return new StockMovementResponse(m.getId(), m.getProductId(), product.sku(), product.name(), m.getType(),
                    m.getQuantity(), Money.of(m.getUnitCostMinor(), currency),
                    m.getValueMinor() == null ? null : Money.of(m.getValueMinor(), currency), m.getStockAfter(),
                    m.getReferenceType(), m.getReferenceId(), m.getNote(), m.getActorId(), m.getCreatedAt());
        }));
    }

    /** Items are paged; totals come from one aggregate query over the exact value pool (== GL 1200). */
    @Transactional(readOnly = true)
    public InventoryValuationResponse valuation(int page, int size) {
        Page<InventoryItemResponse> items = products.findByStockGreaterThan(0, PageRequest.of(page, size, Sort.by("sku")))
                .map(this::toItem);
        StockTotals totals = products.stockTotals();
        return new InventoryValuationResponse(PageResponse.of(items), totals.units(),
                Money.of(totals.valueMinor(), currency), Instant.now());
    }

    public InventoryItemResponse toItem(Product p) {
        return new InventoryItemResponse(p.getId(), p.getSku(), p.getName(), p.isActive(), p.getStock(),
                p.getReserved(), p.getAvailable(), Money.of(p.getCostMinor(), currency),
                Money.of(p.getStockValueMinor(), currency), p.getReorderPoint(), p.getReorderQuantity(),
                p.getPreferredSupplierId());
    }

    private StockMovement save(Product product, MovementType type, int quantity, long unitCost, long value,
                               String referenceType, UUID referenceId, String note) {
        String trimmedNote = note == null || note.length() <= 300 ? note : note.substring(0, 300);
        return movements.save(new StockMovement(product.getId(), type, quantity, unitCost, value, product.getStock(),
                referenceType, referenceId, trimmedNote,
                CurrentUser.find().map(AuthenticatedPrincipal::userId).orElse(null)));
    }

    /** Display unit cost of a movement (the exact figure is its value). */
    private static long unitCostOf(long value, int quantity) {
        return (value + quantity / 2) / quantity;
    }
}
