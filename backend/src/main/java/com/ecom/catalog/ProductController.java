package com.ecom.catalog;

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

import com.ecom.catalog.CatalogDtos.CreateProductRequest;
import com.ecom.catalog.CatalogDtos.ProductResponse;
import com.ecom.catalog.CatalogDtos.UpdateProductRequest;
import com.ecom.common.error.ApiException;
import com.ecom.common.error.ErrorCode;
import com.ecom.common.web.PageResponse;
import com.ecom.security.CurrentUser;
import com.ecom.security.Scopes;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/v1/products")
@Tag(name = "Catalog")
public class ProductController {

    private final CatalogService catalogService;

    public ProductController(CatalogService catalogService) {
        this.catalogService = catalogService;
    }

    @GetMapping
    @Operation(operationId = "searchProducts",
            summary = "Search active products by text and/or category. Public; no authentication required.")
    public PageResponse<ProductResponse> search(
            @RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(defaultValue = "false") boolean includeInactive,
            @RequestParam(defaultValue = "NEWEST") ProductSort sort,
            @RequestParam(defaultValue = "0") @Min(0) @Max(10_000) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return catalogService.searchProducts(q, categoryId, requireAdminIf(includeInactive), sort, page, size);
    }

    @GetMapping("/{id}")
    @Operation(operationId = "getProduct", summary = "Get one product by id. Public; no authentication required.")
    public ProductResponse get(@PathVariable UUID id,
                               @RequestParam(defaultValue = "false") boolean includeInactive) {
        return catalogService.getProduct(id, requireAdminIf(includeInactive));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('SCOPE_catalog:write')")
    @Operation(operationId = "createProduct", summary = "Create a product, optionally with opening stock at a unit cost (admin)")
    public ResponseEntity<ProductResponse> create(@Valid @RequestBody CreateProductRequest request) {
        ProductResponse created = catalogService.createProduct(request);
        return ResponseEntity.created(URI.create("/api/v1/products/" + created.id())).body(created);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('SCOPE_catalog:write')")
    @Operation(operationId = "updateProduct", summary = "Replace a product's details; stock is changed via inventory endpoints (admin)")
    public ProductResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateProductRequest request) {
        return catalogService.updateProduct(id, request);
    }


    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('SCOPE_catalog:write')")
    @Operation(operationId = "deactivateProduct", summary = "Hide a product from the catalog (soft delete, admin)")
    public void deactivate(@PathVariable UUID id) {
        catalogService.deactivateProduct(id);
    }

    private static boolean requireAdminIf(boolean includeInactive) {
        if (includeInactive && !CurrentUser.hasScope(Scopes.CATALOG_WRITE)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "includeInactive requires the catalog:write scope");
        }
        return includeInactive;
    }
}
