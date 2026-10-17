package com.ecom.catalog;

import java.net.URI;
import java.util.List;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.ecom.catalog.CatalogDtos.CategoryRequest;
import com.ecom.catalog.CatalogDtos.CategoryResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/categories")
@Tag(name = "Catalog")
public class CategoryController {

    private final CatalogService catalogService;

    public CategoryController(CatalogService catalogService) {
        this.catalogService = catalogService;
    }

    @GetMapping
    @Operation(operationId = "listCategories", summary = "List all product categories. Public.")
    public List<CategoryResponse> list() {
        return catalogService.listCategories();
    }

    @GetMapping("/{id}")
    @Operation(operationId = "getCategory", summary = "Get one category by id. Public.")
    public CategoryResponse get(@PathVariable UUID id) {
        return catalogService.getCategory(id);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('SCOPE_catalog:write')")
    @Operation(operationId = "createCategory", summary = "Create a category (admin)")
    public ResponseEntity<CategoryResponse> create(@Valid @RequestBody CategoryRequest request) {
        CategoryResponse created = catalogService.createCategory(request);
        return ResponseEntity.created(URI.create("/api/v1/categories/" + created.id())).body(created);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('SCOPE_catalog:write')")
    @Operation(operationId = "updateCategory", summary = "Rename or re-describe a category (admin)")
    public CategoryResponse update(@PathVariable UUID id, @Valid @RequestBody CategoryRequest request) {
        return catalogService.updateCategory(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('SCOPE_catalog:write')")
    @Operation(operationId = "deleteCategory", summary = "Delete an empty category (admin)")
    public void delete(@PathVariable UUID id) {
        catalogService.deleteCategory(id);
    }
}
