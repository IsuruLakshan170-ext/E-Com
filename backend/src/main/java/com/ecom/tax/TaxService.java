package com.ecom.tax;

import java.math.BigDecimal;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ecom.audit.AuditAction;
import com.ecom.audit.AuditService;
import com.ecom.common.error.ApiException;
import com.ecom.common.error.ErrorCode;
import com.ecom.tax.TaxDtos.TaxRateRequest;
import com.ecom.tax.TaxDtos.TaxRateResponse;

@Service
public class TaxService {

    private static final Pattern COUNTRY = Pattern.compile("^[A-Z]{2}$");

    private final TaxRateRepository taxRates;
    private final AuditService audit;

    public TaxService(TaxRateRepository taxRates, AuditService audit) {
        this.taxRates = taxRates;
        this.audit = audit;
    }

    /** 0 when no active rate is configured for the country (prices are tax-exclusive). */
    @Transactional(readOnly = true)
    public int rateBasisPointsFor(String countryCode) {
        return taxRates.findByCountryCode(countryCode).filter(TaxRate::isActive)
                .map(TaxRate::getRateBasisPoints).orElse(0);
    }

    /** Round half-up to the minor unit. */
    public static long taxFor(long amountMinor, int rateBasisPoints) {
        return (Math.multiplyExact(amountMinor, (long) rateBasisPoints) + 5_000) / 10_000;
    }

    @Transactional(readOnly = true)
    public List<TaxRateResponse> list() {
        return taxRates.findAllByOrderByCountryCodeAsc().stream().map(TaxService::toResponse).toList();
    }

    @Transactional
    public TaxRateResponse upsert(String countryCode, TaxRateRequest request) {
        if (countryCode == null || !COUNTRY.matcher(countryCode).matches()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "countryCode must be an ISO 3166-1 alpha-2 code, e.g. US");
        }
        TaxRate rate = taxRates.findByCountryCode(countryCode).orElseGet(() -> new TaxRate(countryCode));
        rate.update(request.name().trim(), request.rateBasisPoints(), request.active());
        taxRates.save(rate);
        audit.record(AuditAction.TAX_RATE_CHANGED, "tax_rate", countryCode,
                "bp=" + request.rateBasisPoints() + ",active=" + request.active());
        return toResponse(rate);
    }

    static TaxRateResponse toResponse(TaxRate r) {
        return new TaxRateResponse(r.getCountryCode(), r.getName(), r.getRateBasisPoints(),
                BigDecimal.valueOf(r.getRateBasisPoints()).movePointLeft(2).toPlainString(), r.isActive());
    }
}
