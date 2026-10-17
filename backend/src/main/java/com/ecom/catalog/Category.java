package com.ecom.catalog;

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

@Entity
@Table(name = "categories")
public class Category {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, length = 100)
    private String name;

    @Column(nullable = false, unique = true, length = 120)
    private String slug;

    @Column(length = 1000)
    private String description;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Category() {
    }

    Category(String name, String slug, String description) {
        update(name, slug, description);
    }

    void update(String name, String slug, String description) {
        this.name = name;
        this.slug = slug;
        this.description = description;
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public String getSlug() { return slug; }
    public String getDescription() { return description; }
}
