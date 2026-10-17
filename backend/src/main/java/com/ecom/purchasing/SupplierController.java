package com.ecom.purchasing;

import java.net.URI;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.ecom.common.web.PageResponse;
import com.ecom.purchasing.PurchasingDtos.SupplierRequest;
import com.ecom.purchasing.PurchasingDtos.SupplierResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/v1/purchasing/suppliers")
@Tag(name = "Purchasing")
public class SupplierController {

    private final SupplierService supplierService;

    public SupplierController(SupplierService supplierService) {
        this.supplierService = supplierService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('SCOPE_purchasing:read')")
    @Operation(operationId = "searchSuppliers", summary = "Search suppliers by name or code")
    public PageResponse<SupplierResponse> search(@RequestParam(required = false) @Size(max = 100) String q,
                                                 @RequestParam(defaultValue = "false") boolean includeInactive,
                                                 @RequestParam(defaultValue = "0") @Min(0) int page,
                                                 @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return supplierService.search(q, includeInactive, page, size);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('SCOPE_purchasing:read')")
    @Operation(operationId = "getSupplier", summary = "Get one supplier")
    public SupplierResponse get(@PathVariable UUID id) {
        return supplierService.get(id);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('SCOPE_purchasing:write')")
    @Operation(operationId = "createSupplier", summary = "Create a supplier")
    public ResponseEntity<SupplierResponse> create(@Valid @RequestBody SupplierRequest request) {
        SupplierResponse created = supplierService.create(request);
        return ResponseEntity.created(URI.create("/api/v1/purchasing/suppliers/" + created.id())).body(created);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('SCOPE_purchasing:write')")
    @Operation(operationId = "updateSupplier", summary = "Replace a supplier's details")
    public SupplierResponse update(@PathVariable UUID id, @Valid @RequestBody SupplierRequest request) {
        return supplierService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('SCOPE_purchasing:write')")
    @Operation(operationId = "deactivateSupplier", summary = "Deactivate a supplier (soft delete)")
    public void deactivate(@PathVariable UUID id) {
        supplierService.deactivate(id);
    }
}
