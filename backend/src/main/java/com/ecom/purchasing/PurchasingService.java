package com.ecom.purchasing;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
import com.ecom.catalog.Product;
import com.ecom.catalog.ProductLabel;
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
import com.ecom.inventory.MovementType;
import com.ecom.purchasing.PurchasingDtos.DraftsFromSuggestionsResponse;
import com.ecom.purchasing.PurchasingDtos.GoodsReceiptLineRequest;
import com.ecom.purchasing.PurchasingDtos.GoodsReceiptLineResponse;
import com.ecom.purchasing.PurchasingDtos.GoodsReceiptRequest;
import com.ecom.purchasing.PurchasingDtos.GoodsReceiptResponse;
import com.ecom.purchasing.PurchasingDtos.PurchaseOrderLineRequest;
import com.ecom.purchasing.PurchasingDtos.PurchaseOrderLineResponse;
import com.ecom.purchasing.PurchasingDtos.PurchaseOrderRequest;
import com.ecom.purchasing.PurchasingDtos.PurchaseOrderResponse;
import com.ecom.purchasing.PurchasingDtos.ReorderSuggestion;
import com.ecom.security.CurrentUser;
import com.ecom.security.SodPolicy;

/**
 * Procure-to-stock: draft -> submit -> approve (by someone else) -> receive (possibly in parts).
 * Receiving updates stock and moving-average cost; finance posts Dr Inventory / Cr Accounts payable from
 * the {@link GoodsReceivedEvent}. Segregation of duties: creator != approver != receiver, enforced through
 * {@link SodPolicy} (strict by default; small-team mode allows one person with an audited reason, R10).
 */
@Service
public class PurchasingService {

    public record ReceiptResult(GoodsReceiptResponse receipt, boolean replayed) {
    }

    public record CreateResult(PurchaseOrderResponse purchaseOrder, boolean replayed) {
    }

    private final PurchaseOrderRepository purchaseOrders;
    private final GoodsReceiptRepository receipts;
    private final ProductRepository products;
    private final SupplierService supplierService;
    private final SupplierRepository suppliers;
    private final ReplenishmentService replenishment;
    private final InventoryService inventory;
    private final IdempotencyService idempotency;
    private final SodPolicy sodPolicy;
    private final ApplicationEventPublisher events;
    private final AuditService audit;
    private final String currency;

    public PurchasingService(PurchaseOrderRepository purchaseOrders, GoodsReceiptRepository receipts,
                             ProductRepository products, SupplierService supplierService, SupplierRepository suppliers,
                             ReplenishmentService replenishment, InventoryService inventory,
                             IdempotencyService idempotency, SodPolicy sodPolicy, ApplicationEventPublisher events,
                             AuditService audit, AppProperties props) {
        this.purchaseOrders = purchaseOrders;
        this.receipts = receipts;
        this.products = products;
        this.supplierService = supplierService;
        this.suppliers = suppliers;
        this.replenishment = replenishment;
        this.inventory = inventory;
        this.idempotency = idempotency;
        this.sodPolicy = sodPolicy;
        this.events = events;
        this.audit = audit;
        this.currency = props.currency();
    }

    /** M5: Idempotency-Key required, so an agent retrying after a timeout never creates a duplicate PO. */
    @Transactional
    public CreateResult create(String idempotencyKey, PurchaseOrderRequest r) {
        UUID creator = CurrentUser.id();
        String fingerprint = idempotency.fingerprint(r);
        var replay = idempotency.replay(creator, "createPurchaseOrder", idempotencyKey, fingerprint);
        if (replay.isPresent()) {
            return new CreateResult(toResponse(find(replay.get())), true);
        }
        supplierService.requireActive(r.supplierId());
        PurchaseOrder po = new PurchaseOrder(DocumentNumberService.randomNumber("PO-"), currency, creator);
        po.editDraft(r.supplierId(), r.expectedDate(), r.notes(), toSpecs(r.lines()));
        purchaseOrders.saveAndFlush(po);
        idempotency.remember(creator, "createPurchaseOrder", idempotencyKey, fingerprint, "purchase_order", po.getId());
        audit.record(AuditAction.PURCHASE_ORDER_CREATED, "purchase_order", po.getId(),
                "number=" + po.getPoNumber() + ",totalMinor=" + po.getTotalMinor());
        return new CreateResult(toResponse(po), false);
    }

    @Transactional
    public PurchaseOrderResponse updateDraft(UUID id, PurchaseOrderRequest r) {
        PurchaseOrder po = find(id);
        requireStatus(po, PurchaseOrderStatus.DRAFT, "Only draft purchase orders can be edited");
        supplierService.requireActive(r.supplierId());
        po.editDraft(r.supplierId(), r.expectedDate(), r.notes(), toSpecs(r.lines()));
        audit.record(AuditAction.PURCHASE_ORDER_UPDATED, "purchase_order", id, "totalMinor=" + po.getTotalMinor());
        return toResponse(po);
    }

    @Transactional
    public PurchaseOrderResponse submit(UUID id) {
        PurchaseOrder po = find(id);
        requireStatus(po, PurchaseOrderStatus.DRAFT, "Only draft purchase orders can be submitted");
        po.transitionTo(PurchaseOrderStatus.SUBMITTED);
        audit.record(AuditAction.PURCHASE_ORDER_SUBMITTED, "purchase_order", id, null);
        return toResponse(po);
    }

    /** Segregation of duties: the approver must not be the creator (small-team mode: allowed with a reason). */
    @Transactional
    public PurchaseOrderResponse approve(UUID id, String sodOverrideReason) {
        PurchaseOrder po = find(id);
        requireStatus(po, PurchaseOrderStatus.SUBMITTED, "Only submitted purchase orders can be approved");
        UUID approver = CurrentUser.id();
        sodPolicy.enforce(approver.equals(po.getCreatedBy()), "approvePurchaseOrder", ErrorCode.SELF_APPROVAL_NOT_ALLOWED,
                "A purchase order must be approved by someone other than its creator", sodOverrideReason,
                "purchase_order", id);
        supplierService.requireActive(po.getSupplierId());
        po.approve(approver, Instant.now());
        audit.record(AuditAction.PURCHASE_ORDER_APPROVED, "purchase_order", id, "totalMinor=" + po.getTotalMinor());
        return toResponse(po);
    }

    @Transactional
    public PurchaseOrderResponse cancel(UUID id) {
        PurchaseOrder po = find(id);
        if (!po.getStatus().allowedNext().contains(PurchaseOrderStatus.CANCELLED)) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION,
                    "Purchase orders that are received (fully or partly) or already cancelled cannot be cancelled",
                    Map.of("status", po.getStatus()));
        }
        po.transitionTo(PurchaseOrderStatus.CANCELLED);
        audit.record(AuditAction.PURCHASE_ORDER_CANCELLED, "purchase_order", id, null);
        return toResponse(po);
    }

    /** Idempotent per (receiver, Idempotency-Key): a retried delivery is never booked twice. */
    @Transactional
    public ReceiptResult receive(UUID id, String idempotencyKey, GoodsReceiptRequest r) {
        IdempotencyKeys.validate(idempotencyKey);
        UUID receiver = CurrentUser.id();
        var previous = receipts.findByReceivedByAndIdempotencyKey(receiver, idempotencyKey);
        if (previous.isPresent()) {
            if (!previous.get().getPurchaseOrderId().equals(id)) {
                throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_REUSED,
                        "This Idempotency-Key was already used for a different purchase order");
            }
            return new ReceiptResult(toResponse(previous.get()), true);
        }

        // M6: lock the PO first (then products, in id order) so concurrent receipts are serialised.
        PurchaseOrder po = purchaseOrders.lockById(id).orElseThrow(() -> ApiException.notFound("Purchase order"));
        if (!po.getStatus().receivable()) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, "Only approved purchase orders can be received",
                    Map.of("status", po.getStatus()));
        }
        sodPolicy.enforce(receiver.equals(po.getApprovedBy()) || receiver.equals(po.getCreatedBy()),
                "receivePurchaseOrder", ErrorCode.SEGREGATION_OF_DUTIES,
                "Goods must be received by someone other than the person who raised or approved the purchase order",
                r.sodOverrideReason(), "purchase_order", id);
        Set<UUID> seen = new HashSet<>();
        for (GoodsReceiptLineRequest line : r.lines()) {
            if (!seen.add(line.productId())) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "Each product may appear only once per receipt",
                        Map.of("productId", line.productId()));
            }
        }

        Map<UUID, Product> locked = products.lockAllByIds(seen).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        GoodsReceipt receipt = new GoodsReceipt(DocumentNumberService.randomNumber("GRN-"), po.getId(),
                idempotencyKey, r.note(), receiver);
        for (GoodsReceiptLineRequest line : r.lines()) {
            PurchaseOrderLine poLine = po.line(line.productId())
                    .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_FAILED,
                            "Product is not on this purchase order", Map.of("productId", line.productId())));
            if (line.quantity() > poLine.outstanding()) {
                throw new ApiException(ErrorCode.OVER_RECEIPT, "Received quantity exceeds what is outstanding",
                        Map.of("productId", line.productId(), "outstanding", poLine.outstanding(),
                                "received", line.quantity()));
            }
            poLine.receive(line.quantity());
            receipt.addLine(line.productId(), line.quantity(), poLine.getUnitCostMinor());
        }
        po.refreshReceiptStatus();
        receipts.saveAndFlush(receipt);

        for (GoodsReceiptLine line : receipt.getLines()) {
            inventory.move(locked.get(line.getProductId()), MovementType.PURCHASE_RECEIPT, line.getQuantity(),
                    line.getUnitCostMinor(), "goods_receipt", receipt.getId(), po.getPoNumber());
        }
        events.publishEvent(new GoodsReceivedEvent(receipt.getId(),
                "Goods receipt " + receipt.getReceiptNumber() + " for " + po.getPoNumber(), receipt.getTotalCostMinor()));
        audit.record(AuditAction.GOODS_RECEIVED, "purchase_order", po.getId(),
                "receipt=" + receipt.getReceiptNumber() + ",costMinor=" + receipt.getTotalCostMinor());
        return new ReceiptResult(toResponse(receipt), false);
    }

    /** One draft PO per active preferred supplier; anything that can't be ordered is reported back, not failed. */
    @Transactional
    public DraftsFromSuggestionsResponse createDraftsFromReorderSuggestions() {
        Map<UUID, List<PoLineSpec>> bySupplier = new LinkedHashMap<>();
        List<UUID> skipped = new ArrayList<>();
        List<UUID> inactiveSuppliers = new ArrayList<>();
        for (ReorderSuggestion s : replenishment.allSuggestions()) {
            if (s.preferredSupplierId() == null) {
                skipped.add(s.productId());
                continue;
            }
            bySupplier.computeIfAbsent(s.preferredSupplierId(), k -> new ArrayList<>())
                    .add(new PoLineSpec(s.productId(), s.suggestedQuantity(), s.estimatedUnitCost().amountMinor()));
        }
        List<PurchaseOrderResponse> created = new ArrayList<>();
        UUID creator = CurrentUser.id();
        for (var entry : bySupplier.entrySet()) {
            if (suppliers.findById(entry.getKey()).filter(Supplier::isActive).isEmpty()) {
                inactiveSuppliers.add(entry.getKey());
                continue;
            }
            PurchaseOrder po = new PurchaseOrder(DocumentNumberService.randomNumber("PO-"), currency, creator);
            po.editDraft(entry.getKey(), null, "Generated from reorder suggestions", entry.getValue());
            purchaseOrders.saveAndFlush(po);
            audit.record(AuditAction.PURCHASE_ORDER_CREATED, "purchase_order", po.getId(),
                    "source=reorder-suggestions,totalMinor=" + po.getTotalMinor());
            created.add(toResponse(po));
        }
        return new DraftsFromSuggestionsResponse(created, skipped, inactiveSuppliers);
    }

    @Transactional(readOnly = true)
    public PageResponse<PurchaseOrderResponse> list(PurchaseOrderStatus status, UUID supplierId, int page, int size) {
        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by("id")));
        Page<PurchaseOrder> result;
        if (status != null && supplierId != null) {
            result = purchaseOrders.findBySupplierIdAndStatus(supplierId, status, pageable);
        } else if (status != null) {
            result = purchaseOrders.findByStatus(status, pageable);
        } else if (supplierId != null) {
            result = purchaseOrders.findBySupplierId(supplierId, pageable);
        } else {
            result = purchaseOrders.findAll(pageable);
        }
        Names names = namesFor(result.getContent());
        return PageResponse.of(result.map(po -> toResponse(po, names)));
    }

    @Transactional(readOnly = true)
    public PurchaseOrderResponse get(UUID id) {
        return toResponse(find(id));
    }

    @Transactional(readOnly = true)
    public List<GoodsReceiptResponse> listReceipts(UUID purchaseOrderId) {
        find(purchaseOrderId);
        List<GoodsReceipt> found = receipts.findByPurchaseOrderIdOrderByReceivedAtAsc(purchaseOrderId);
        Map<UUID, ProductLabel> labels = products.labelsById(found.stream()
                .flatMap(gr -> gr.getLines().stream()).map(GoodsReceiptLine::getProductId).toList());
        return found.stream().map(gr -> toResponse(gr, labels)).toList();
    }

    private List<PoLineSpec> toSpecs(List<PurchaseOrderLineRequest> lines) {
        Set<UUID> ids = new HashSet<>();
        for (PurchaseOrderLineRequest line : lines) {
            if (!ids.add(line.productId())) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "Each product may appear only once per purchase order",
                        Map.of("productId", line.productId()));
            }
        }
        Map<UUID, Product> found = products.findAllById(ids).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        List<UUID> missing = ids.stream().filter(pid -> found.get(pid) == null || !found.get(pid).isActive()).toList();
        if (!missing.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Some products do not exist or are inactive",
                    Map.of("productIds", missing));
        }
        return lines.stream().map(l -> new PoLineSpec(l.productId(), l.quantity(), l.unitCostMinor())).toList();
    }

    private PurchaseOrder find(UUID id) {
        return purchaseOrders.findById(id).orElseThrow(() -> ApiException.notFound("Purchase order"));
    }

    private static void requireStatus(PurchaseOrder po, PurchaseOrderStatus expected, String message) {
        if (po.getStatus() != expected) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, message, Map.of("status", po.getStatus()));
        }
    }

    /** BE-3: the product and supplier names a response carries, loaded once per response or page (never per row). */
    private record Names(Map<UUID, ProductLabel> products, Map<UUID, String> suppliers) {
        ProductLabel product(UUID id) {
            return products.getOrDefault(id, ProductLabel.missing(id));
        }
    }

    private Names namesFor(Collection<PurchaseOrder> orders) {
        Map<UUID, ProductLabel> productLabels = products.labelsById(orders.stream()
                .flatMap(po -> po.getLines().stream()).map(PurchaseOrderLine::getProductId).toList());
        Set<UUID> supplierIds = orders.stream().map(PurchaseOrder::getSupplierId).collect(Collectors.toSet());
        Map<UUID, String> supplierNames = supplierIds.isEmpty() ? Map.of()
                : suppliers.findAllById(supplierIds).stream().collect(Collectors.toMap(Supplier::getId, Supplier::getName));
        return new Names(productLabels, supplierNames);
    }

    private PurchaseOrderResponse toResponse(PurchaseOrder po) {
        return toResponse(po, namesFor(List.of(po)));
    }

    private PurchaseOrderResponse toResponse(PurchaseOrder po, Names names) {
        List<PurchaseOrderLineResponse> lines = po.getLines().stream()
                .map(l -> {
                    ProductLabel product = names.product(l.getProductId());
                    return new PurchaseOrderLineResponse(l.getLineNo(), l.getProductId(), product.sku(), product.name(),
                            l.getQuantityOrdered(), l.getQuantityReceived(), l.getQuantityOrdered() - l.getQuantityReceived(),
                            Money.of(l.getUnitCostMinor(), po.getCurrency()), Money.of(l.getLineTotalMinor(), po.getCurrency()));
                })
                .toList();
        return new PurchaseOrderResponse(po.getId(), po.getPoNumber(), po.getSupplierId(),
                names.suppliers().get(po.getSupplierId()), po.getStatus(), lines,
                Money.of(po.getTotalMinor(), po.getCurrency()), po.getExpectedDate(), po.getNotes(), po.getCreatedBy(),
                po.getApprovedBy(), po.getApprovedAt(), po.getStatus().allowedActions(), po.getCreatedAt(), po.getUpdatedAt());
    }

    private GoodsReceiptResponse toResponse(GoodsReceipt gr) {
        return toResponse(gr, products.labelsById(gr.getLines().stream().map(GoodsReceiptLine::getProductId).toList()));
    }

    private GoodsReceiptResponse toResponse(GoodsReceipt gr, Map<UUID, ProductLabel> labels) {
        return new GoodsReceiptResponse(gr.getId(), gr.getReceiptNumber(), gr.getPurchaseOrderId(),
                gr.getLines().stream()
                        .map(l -> {
                            ProductLabel product = labels.getOrDefault(l.getProductId(), ProductLabel.missing(l.getProductId()));
                            return new GoodsReceiptLineResponse(l.getProductId(), product.sku(), product.name(),
                                    l.getQuantity(), Money.of(l.getUnitCostMinor(), currency));
                        })
                        .toList(),
                Money.of(gr.getTotalCostMinor(), currency), gr.getNote(), gr.getReceivedBy(), gr.getReceivedAt());
    }
}
