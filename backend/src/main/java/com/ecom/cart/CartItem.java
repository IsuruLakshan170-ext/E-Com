package com.ecom.cart;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "cart_items")
public class CartItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cart_id", nullable = false, updatable = false)
    private Cart cart;

    @Column(name = "product_id", nullable = false, updatable = false)
    private UUID productId;

    @Column(nullable = false)
    private int quantity;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected CartItem() {
    }

    CartItem(Cart cart, UUID productId, int quantity) {
        this.cart = cart;
        this.productId = productId;
        this.quantity = quantity;
    }

    void setQuantity(int quantity) {
        this.quantity = quantity;
    }

    public UUID getProductId() { return productId; }
    public int getQuantity() { return quantity; }
}
