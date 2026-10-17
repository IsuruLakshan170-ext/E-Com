package com.ecom.common.web;

import java.util.List;

import org.springframework.data.domain.Page;

public record PageResponse<T>(List<T> items, int page, int size, long totalItems, int totalPages, boolean hasNext) {

    public static <T> PageResponse<T> of(Page<T> page) {
        return new PageResponse<>(page.getContent(), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages(), page.hasNext());
    }
}
