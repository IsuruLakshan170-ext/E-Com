package com.ecom.tax;

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

/** Sales tax per destination country, in basis points (1000 = 10.00%). */
@Entity
@Table(name = "tax_rates")
public class TaxRate {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "country_code", nullable = false, unique = true, length = 2, updatable = false)
    private String countryCode;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "rate_basis_points", nullable = false)
    private int rateBasisPoints;

    @Column(nullable = false)
    private boolean active;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected TaxRate() {
    }

    TaxRate(String countryCode) {
        this.countryCode = countryCode;
    }

    void update(String name, int rateBasisPoints, boolean active) {
        this.name = name;
        this.rateBasisPoints = rateBasisPoints;
        this.active = active;
    }

    public String getCountryCode() { return countryCode; }
    public String getName() { return name; }
    public int getRateBasisPoints() { return rateBasisPoints; }
    public boolean isActive() { return active; }
}
