package com.ecom.tax;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TaxRateRepository extends JpaRepository<TaxRate, UUID> {

    Optional<TaxRate> findByCountryCode(String countryCode);

    List<TaxRate> findAllByOrderByCountryCodeAsc();
}
