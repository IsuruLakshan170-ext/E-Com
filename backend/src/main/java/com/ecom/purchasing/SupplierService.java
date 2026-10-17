package com.ecom.purchasing;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ecom.audit.AuditAction;
import com.ecom.audit.AuditService;
import com.ecom.common.error.ApiException;
import com.ecom.common.error.ErrorCode;
import com.ecom.common.util.LikePatterns;
import com.ecom.common.web.PageResponse;
import com.ecom.purchasing.PurchasingDtos.SupplierRequest;
import com.ecom.purchasing.PurchasingDtos.SupplierResponse;

import jakarta.persistence.criteria.Predicate;

@Service
public class SupplierService {

    private final SupplierRepository suppliers;
    private final AuditService audit;

    public SupplierService(SupplierRepository suppliers, AuditService audit) {
        this.suppliers = suppliers;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public PageResponse<SupplierResponse> search(String query, boolean includeInactive, int page, int size) {
        Specification<Supplier> spec = (root, cq, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (!includeInactive) {
                predicates.add(cb.isTrue(root.<Boolean>get("active")));
            }
            if (query != null && !query.isBlank()) {
                String pattern = LikePatterns.contains(query);
                predicates.add(cb.or(
                        cb.like(cb.lower(root.<String>get("name")), pattern, LikePatterns.ESCAPE),
                        cb.like(cb.lower(root.<String>get("code")), pattern, LikePatterns.ESCAPE)));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
        return PageResponse.of(suppliers.findAll(spec, PageRequest.of(page, size, Sort.by("name").and(Sort.by("id"))))
                .map(SupplierResponse::from));
    }

    @Transactional(readOnly = true)
    public SupplierResponse get(UUID id) {
        return suppliers.findById(id).map(SupplierResponse::from).orElseThrow(() -> ApiException.notFound("Supplier"));
    }

    @Transactional
    public SupplierResponse create(SupplierRequest r) {
        if (suppliers.existsByCode(r.code())) {
            throw new ApiException(ErrorCode.CONFLICT, "A supplier with this code already exists", Map.of("code", r.code()));
        }
        Supplier supplier = new Supplier(r.code());
        apply(supplier, r, true);
        suppliers.saveAndFlush(supplier);
        audit.record(AuditAction.SUPPLIER_CREATED, "supplier", supplier.getId(), "code=" + r.code());
        return SupplierResponse.from(supplier);
    }

    @Transactional
    public SupplierResponse update(UUID id, SupplierRequest r) {
        Supplier supplier = suppliers.findById(id).orElseThrow(() -> ApiException.notFound("Supplier"));
        if (suppliers.existsByCodeAndIdNot(r.code(), id)) {
            throw new ApiException(ErrorCode.CONFLICT, "A supplier with this code already exists", Map.of("code", r.code()));
        }
        apply(supplier, r, supplier.isActive());
        audit.record(AuditAction.SUPPLIER_UPDATED, "supplier", id, null);
        return SupplierResponse.from(supplier);
    }

    /** Soft delete: historic purchase orders keep pointing at the supplier. */
    @Transactional
    public void deactivate(UUID id) {
        Supplier supplier = suppliers.findById(id).orElseThrow(() -> ApiException.notFound("Supplier"));
        supplier.deactivate();
        audit.record(AuditAction.SUPPLIER_DEACTIVATED, "supplier", id, null);
    }

    Supplier requireActive(UUID id) {
        return suppliers.findById(id).filter(Supplier::isActive)
                .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_FAILED, "Supplier does not exist or is inactive",
                        Map.of("supplierId", id)));
    }

    private static void apply(Supplier s, SupplierRequest r, boolean defaultActive) {
        s.update(r.code(), r.name().trim(), r.email(), r.phone(), r.address(), r.paymentTermsDays(),
                r.active() == null ? defaultActive : r.active());
    }
}
