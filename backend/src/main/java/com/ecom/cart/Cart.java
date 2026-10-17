package com.ecom.cart;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "carts")
public class Cart {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false, unique = true, updatable = false)
    private UUID userId;

    @OneToMany(mappedBy = "cart", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("createdAt ASC")
    private List<CartItem> items = new ArrayList<>();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Bumped on every change (via touch) so two concurrent edits of the same cart can't silently overwrite. */
    @Version
    private long version;

    protected Cart() {
    }

    Cart(UUID userId) {
        this.userId = userId;
        this.updatedAt = Instant.now();
    }

    Optional<CartItem> findItem(UUID productId) {
        return items.stream().filter(i -> i.getProductId().equals(productId)).findFirst();
    }

    void putItem(UUID productId, int quantity) {
        findItem(productId).ifPresentOrElse(
                item -> item.setQuantity(quantity),
                () -> items.add(new CartItem(this, productId, quantity)));
        touch();
    }

    void removeItem(UUID productId) {
        items.removeIf(i -> i.getProductId().equals(productId));
        touch();
    }

    public void clear() {
        items.clear();
        touch();
    }

    public Set<UUID> productIds() {
        Set<UUID> ids = new LinkedHashSet<>();
        items.forEach(i -> ids.add(i.getProductId()));
        return ids;
    }

    private void touch() {
        updatedAt = Instant.now();
    }

    public UUID getUserId() { return userId; }
    public List<CartItem> getItems() { return items; }
    public Instant getUpdatedAt() { return updatedAt; }
}
