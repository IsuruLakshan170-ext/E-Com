package com.ecom.catalog;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CategoryRepository extends JpaRepository<Category, UUID> {

    List<Category> findAllByOrderByNameAsc();

    boolean existsBySlug(String slug);

    boolean existsBySlugAndIdNot(String slug, UUID id);
}
