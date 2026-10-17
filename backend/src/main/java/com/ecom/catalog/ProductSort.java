package com.ecom.catalog;

import org.springframework.data.domain.Sort;

/** Whitelisted sort orders; free-form sort fields are never passed to the database. */
public enum ProductSort {
    NEWEST(Sort.by(Sort.Direction.DESC, "createdAt")),
    NAME_ASC(Sort.by(Sort.Direction.ASC, "name")),
    PRICE_ASC(Sort.by(Sort.Direction.ASC, "priceMinor")),
    PRICE_DESC(Sort.by(Sort.Direction.DESC, "priceMinor"));

    private final Sort sort;

    ProductSort(Sort sort) {
        this.sort = sort.and(Sort.by("id")); // stable paging
    }

    public Sort sort() {
        return sort;
    }
}
