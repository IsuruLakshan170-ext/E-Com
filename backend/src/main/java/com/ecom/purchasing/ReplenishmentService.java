package com.ecom.purchasing;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
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
import com.ecom.common.error.ApiException;
import com.ecom.common.error.ErrorCode;
import com.ecom.common.web.Money;
import com.ecom.common.web.PageResponse;
import com.ecom.config.AppProperties;
import com.ecom.inventory.InventoryDtos.InventoryItemResponse;
import com.ecom.inventory.InventoryService;
import com.ecom.purchasing.PurchasingDtos.ReorderPolicyRequest;
import com.ecom.purchasing.PurchasingDtos.ReorderSuggestion;

/**
 * Reorder points and suggestions. Lives in purchasing (it needs open POs and suppliers), which keeps
 * inventory free of any purchasing dependency (M12).
 */
@Service
public class ReplenishmentService {

    /** Outstanding quantity on these POs counts as "on order" (drafts too, so suggestions aren't duplicated). */
    private static final EnumSet<PurchaseOrderStatus> OPEN_PO_STATUSES = EnumSet.of(
            PurchaseOrderStatus.DRAFT, PurchaseOrderStatus.SUBMITTED,
            PurchaseOrderStatus.APPROVED, PurchaseOrderStatus.PARTIALLY_RECEIVED);

    private final ProductRepository products;
    private final PurchaseOrderLineRepository purchaseOrderLines;
    private final SupplierRepository suppliers;
    private final InventoryService inventory;
    private final AuditService audit;
    private final String currency;

    public ReplenishmentService(ProductRepository products, PurchaseOrderLineRepository purchaseOrderLines,
                                SupplierRepository suppliers, InventoryService inventory, AuditService audit,
                                AppProperties props) {
        this.products = products;
        this.purchaseOrderLines = purchaseOrderLines;
        this.suppliers = suppliers;
        this.inventory = inventory;
        this.audit = audit;
        this.currency = props.currency();
    }

    @Transactional
    public InventoryItemResponse setReorderPolicy(UUID productId, ReorderPolicyRequest request) {
        Product product = products.findById(productId).orElseThrow(() -> ApiException.notFound("Product"));
        if (request.preferredSupplierId() != null
                && suppliers.findById(request.preferredSupplierId()).filter(Supplier::isActive).isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Preferred supplier does not exist or is inactive",
                    Map.of("preferredSupplierId", request.preferredSupplierId()));
        }
        product.setReorderPolicy(request.reorderPoint(), request.reorderQuantity(), request.preferredSupplierId());
        audit.record(AuditAction.REORDER_POLICY_CHANGED, "product", productId,
                "point=" + request.reorderPoint() + ",qty=" + request.reorderQuantity());
        return inventory.toItem(product);
    }

    /** M11: paged over candidate products; candidates already covered by open POs are filtered out of the page. */
    @Transactional(readOnly = true)
    public PageResponse<ReorderSuggestion> suggestions(int page, int size) {
        Page<Product> candidates = products.findReorderCandidates(PageRequest.of(page, size, Sort.by("sku")));
        Map<UUID, Long> onOrder = onOrderByProduct();
        Map<UUID, String> supplierNames = supplierNames(candidates.getContent());
        List<ReorderSuggestion> items = candidates.getContent().stream()
                .map(p -> suggest(p, onOrder, supplierNames)).filter(s -> s != null).toList();
        return new PageResponse<>(items, candidates.getNumber(), candidates.getSize(), candidates.getTotalElements(),
                candidates.getTotalPages(), candidates.hasNext());
    }

    /** Full list for the explicit "create draft POs" batch action. */
    @Transactional(readOnly = true)
    public List<ReorderSuggestion> allSuggestions() {
        Map<UUID, Long> onOrder = onOrderByProduct();
        List<Product> candidates = products.findAllReorderCandidates();
        Map<UUID, String> supplierNames = supplierNames(candidates);
        List<ReorderSuggestion> result = new ArrayList<>();
        for (Product p : candidates) {
            ReorderSuggestion s = suggest(p, onOrder, supplierNames);
            if (s != null) {
                result.add(s);
            }
        }
        return result;
    }

    private Map<UUID, Long> onOrderByProduct() {
        return purchaseOrderLines.outstandingByProduct(OPEN_PO_STATUSES).stream()
                .collect(Collectors.toMap(PurchaseOrderLineRepository.OutstandingQuantity::getProductId,
                        PurchaseOrderLineRepository.OutstandingQuantity::getQuantity));
    }

    /** BE-3: preferred-supplier names for a batch of products in one query. */
    private Map<UUID, String> supplierNames(List<Product> candidates) {
        Set<UUID> ids = candidates.stream().map(Product::getPreferredSupplierId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        return ids.isEmpty() ? Map.of()
                : suppliers.findAllById(ids).stream().collect(Collectors.toMap(Supplier::getId, Supplier::getName));
    }

    private ReorderSuggestion suggest(Product p, Map<UUID, Long> onOrder, Map<UUID, String> supplierNames) {
        long incoming = onOrder.getOrDefault(p.getId(), 0L);
        long projected = p.getAvailable() + incoming;
        if (projected > p.getReorderPoint()) {
            return null;
        }
        int shortfall = (int) Math.min(Integer.MAX_VALUE, p.getReorderPoint() - projected + 1);
        int suggested = Math.max(p.getReorderQuantity(), shortfall);
        return new ReorderSuggestion(p.getId(), p.getSku(), p.getName(), p.getStock(), p.getReserved(),
                p.getAvailable(), incoming, p.getReorderPoint(), suggested, p.getPreferredSupplierId(),
                p.getPreferredSupplierId() == null ? null : supplierNames.get(p.getPreferredSupplierId()),
                Money.of(p.getCostMinor(), currency));
    }
}
