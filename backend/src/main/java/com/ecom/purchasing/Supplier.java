package com.ecom.purchasing;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "suppliers")
public class Supplier {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, length = 32)
    private String code;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(length = 254)
    private String email;

    @Column(length = 40)
    private String phone;

    @Column(length = 500)
    private String address;

    @Column(name = "payment_terms_days", nullable = false)
    private int paymentTermsDays;

    @Column(nullable = false)
    private boolean active = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Supplier() {
    }

    Supplier(String code) {
        this.code = code;
    }

    void update(String code, String name, String email, String phone, String address, int paymentTermsDays, boolean active) {
        this.code = code;
        this.name = name;
        this.email = email;
        this.phone = phone;
        this.address = address;
        this.paymentTermsDays = paymentTermsDays;
        this.active = active;
    }

    void deactivate() {
        this.active = false;
    }

    public UUID getId() { return id; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public String getEmail() { return email; }
    public String getPhone() { return phone; }
    public String getAddress() { return address; }
    public int getPaymentTermsDays() { return paymentTermsDays; }
    public boolean isActive() { return active; }
    public Instant getCreatedAt() { return createdAt; }
}
