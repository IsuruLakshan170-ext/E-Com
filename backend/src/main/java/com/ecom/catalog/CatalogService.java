package com.ecom.catalog;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ecom.audit.AuditAction;
import com.ecom.audit.AuditService;
import com.ecom.catalog.CatalogDtos.CategoryRequest;
import com.ecom.catalog.CatalogDtos.CategoryResponse;
import com.ecom.catalog.CatalogDtos.CreateProductRequest;
import com.ecom.catalog.CatalogDtos.ProductResponse;
import com.ecom.catalog.CatalogDtos.UpdateProductRequest;
import com.ecom.common.error.ApiException;
import com.ecom.common.error.ErrorCode;
import com.ecom.common.util.LikePatterns;
import com.ecom.common.web.PageResponse;
import com.ecom.config.AppProperties;

import jakarta.persistence.criteria.Predicate;

@Service
public class CatalogService {

    private final ProductRepository products;
    private final CategoryRepository categories;
    private final ApplicationEventPublisher events;
    private final AuditService audit;
    private final String currency;

    public CatalogService(ProductRepository products, CategoryRepository categories, ApplicationEventPublisher events,
                          AuditService audit, AppProperties props) {
        this.products = products;
        this.categories = categories;
        this.events = events;
        this.audit = audit;
        this.currency = props.currency();
    }

    // ----- products -----

    @Transactional(readOnly = true)
    public PageResponse<ProductResponse> searchProducts(String query, UUID categoryId, boolean includeInactive,
                                                        ProductSort sort, int page, int size) {
        Specification<Product> spec = (root, cq, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (!includeInactive) {
                predicates.add(cb.isTrue(root.<Boolean>get("active")));
            }
            if (query != null && !query.isBlank()) {
                String pattern = LikePatterns.contains(query);
                predicates.add(cb.or(
                        cb.like(cb.lower(root.<String>get("name")), pattern, LikePatterns.ESCAPE),
                        cb.like(cb.lower(root.<String>get("sku")), pattern, LikePatterns.ESCAPE)));
            }
            if (categoryId != null) {
                predicates.add(cb.equal(root.get("categoryId"), categoryId));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
        return PageResponse.of(products.findAll(spec, PageRequest.of(page, size, sort.sort())).map(ProductResponse::from));
    }

    @Transactional(readOnly = true)
    public ProductResponse getProduct(UUID id, boolean includeInactive) {
        return products.findById(id)
                .filter(p -> includeInactive || p.isActive())
                .map(ProductResponse::from)
                .orElseThrow(() -> ApiException.notFound("Product"));
    }

    @Transactional
    public ProductResponse createProduct(CreateProductRequest r) {
        if (products.existsBySku(r.sku())) {
            throw new ApiException(ErrorCode.CONFLICT, "A product with this SKU already exists", Map.of("sku", r.sku()));
        }
        int initialStock = r.initialStock() == null ? 0 : r.initialStock();
        long unitCost = r.unitCostMinor() == null ? 0 : r.unitCostMinor();
        if (initialStock > 0 && r.unitCostMinor() == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "unitCostMinor is required when initialStock > 0");
        }
        requireCategory(r.categoryId());

        Product product = new Product(r.sku(), r.name().trim(), r.description(), r.imageUrl(), r.priceMinor(), currency,
                r.categoryId(), r.active() == null || r.active());
        product.initCost(unitCost);
        products.saveAndFlush(product);
        // Inventory listens and books the opening balance in this same transaction.
        events.publishEvent(new ProductCreatedEvent(product.getId(), initialStock, unitCost));
        audit.record(AuditAction.PRODUCT_CREATED, "product", product.getId(),
                "sku=" + product.getSku() + ",openingStock=" + initialStock);
        return ProductResponse.from(product);
    }

    @Transactional
    public ProductResponse updateProduct(UUID id, UpdateProductRequest r) {
        Product product = products.findById(id).orElseThrow(() -> ApiException.notFound("Product"));
        if (products.existsBySkuAndIdNot(r.sku(), id)) {
            throw new ApiException(ErrorCode.CONFLICT, "A product with this SKU already exists", Map.of("sku", r.sku()));
        }
        requireCategory(r.categoryId());
        product.update(r.sku(), r.name().trim(), r.description(), r.imageUrl(), r.priceMinor(), r.categoryId(),
                r.active() == null ? product.isActive() : r.active());
        audit.record(AuditAction.PRODUCT_UPDATED, "product", id, "price=" + r.priceMinor());
        return ProductResponse.from(product);
    }

    /** Soft delete: products referenced by past orders must keep existing. */
    @Transactional
    public void deactivateProduct(UUID id) {
        Product product = products.findById(id).orElseThrow(() -> ApiException.notFound("Product"));
        product.deactivate();
        audit.record(AuditAction.PRODUCT_DEACTIVATED, "product", id, null);
    }

    // ----- categories -----

    @Transactional(readOnly = true)
    public List<CategoryResponse> listCategories() {
        return categories.findAllByOrderByNameAsc().stream().map(CategoryResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public CategoryResponse getCategory(UUID id) {
        return categories.findById(id).map(CategoryResponse::from).orElseThrow(() -> ApiException.notFound("Category"));
    }

    @Transactional
    public CategoryResponse createCategory(CategoryRequest r) {
        String slug = slugify(r.name());
        if (categories.existsBySlug(slug)) {
            throw new ApiException(ErrorCode.CONFLICT, "A category with this name already exists", Map.of("slug", slug));
        }
        Category category = categories.save(new Category(r.name().trim(), slug, r.description()));
        audit.record(AuditAction.CATEGORY_CREATED, "category", category.getId(), null);
        return CategoryResponse.from(category);
    }

    @Transactional
    public CategoryResponse updateCategory(UUID id, CategoryRequest r) {
        Category category = categories.findById(id).orElseThrow(() -> ApiException.notFound("Category"));
        String slug = slugify(r.name());
        if (categories.existsBySlugAndIdNot(slug, id)) {
            throw new ApiException(ErrorCode.CONFLICT, "A category with this name already exists", Map.of("slug", slug));
        }
        category.update(r.name().trim(), slug, r.description());
        audit.record(AuditAction.CATEGORY_UPDATED, "category", id, null);
        return CategoryResponse.from(category);
    }

    @Transactional
    public void deleteCategory(UUID id) {
        Category category = categories.findById(id).orElseThrow(() -> ApiException.notFound("Category"));
        if (products.existsByCategoryId(id)) {
            throw new ApiException(ErrorCode.CONFLICT, "Category still has products; move or deactivate them first");
        }
        categories.delete(category);
        audit.record(AuditAction.CATEGORY_DELETED, "category", id, null);
    }

    private void requireCategory(UUID categoryId) {
        if (categoryId != null && !categories.existsById(categoryId)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Category does not exist", Map.of("categoryId", categoryId));
        }
    }

    private static String slugify(String name) {
        String slug = Normalizer.normalize(name, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+|-+$)", "");
        if (slug.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Category name must contain letters or digits");
        }
        return slug.length() > 120 ? slug.substring(0, 120) : slug;
    }
}
