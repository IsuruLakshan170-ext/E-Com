package com.ecom.tax;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class TaxDtos {

    private TaxDtos() {
    }

    public record TaxRateRequest(
            @NotBlank @Size(max = 100) String name,
            @NotNull @Min(0) @Max(5000) Integer rateBasisPoints,
            @NotNull Boolean active) {
    }

    public record TaxRateResponse(String countryCode, String name, int rateBasisPoints, String ratePercent,
                                  boolean active) {
    }
}
