package com.ecom.tax;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ecom.tax.TaxDtos.TaxRateRequest;
import com.ecom.tax.TaxDtos.TaxRateResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;

/** Same URLs as before the move out of the finance package. */
@RestController
@RequestMapping("/api/v1/finance/tax-rates")
@Tag(name = "Finance")
public class TaxController {

    private final TaxService taxService;

    public TaxController(TaxService taxService) {
        this.taxService = taxService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('SCOPE_finance:read')")
    @Operation(operationId = "listTaxRates", summary = "List sales tax rates by destination country")
    public List<TaxRateResponse> list() {
        return taxService.list();
    }

    @PutMapping("/{countryCode}")
    @PreAuthorize("hasAuthority('SCOPE_finance:write')")
    @Operation(operationId = "upsertTaxRate", summary = "Create or update the tax rate for a country (basis points, 1000 = 10%)")
    public TaxRateResponse upsert(@PathVariable @Pattern(regexp = "^[A-Z]{2}$") String countryCode,
                                  @Valid @RequestBody TaxRateRequest request) {
        return taxService.upsert(countryCode, request);
    }
}
