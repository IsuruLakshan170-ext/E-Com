package com.ecom.returns;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.ecom.common.web.Money;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class ReturnDtos {

    private ReturnDtos() {
    }

    public record ReturnLineRequest(@NotNull UUID productId, @NotNull @Min(1) @Max(1000) Integer quantity) {
    }

    public record CreateReturnRequest(
            @NotBlank @Size(max = 500) String reason,
            @NotEmpty @Size(max = 50) List<@Valid @NotNull ReturnLineRequest> lines) {
    }

    public record ReturnDecisionRequest(@Size(max = 500) String note) {
    }

    public record ReturnLineResponse(UUID productId, String sku, String productName, int quantity, Money unitPrice,
                                     Money lineTotal) {
    }

    public record ReturnResponse(UUID id, String rmaNumber, UUID orderId, ReturnStatus status, String reason,
                                 String resolutionNote, List<ReturnLineResponse> lines, Money merchandiseTotal,
                                 UUID refundId, Set<ReturnStatus> allowedNextStatuses, Instant createdAt,
                                 Instant updatedAt) {
    }
}
