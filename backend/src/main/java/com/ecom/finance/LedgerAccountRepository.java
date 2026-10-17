package com.ecom.finance;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface LedgerAccountRepository extends JpaRepository<LedgerAccount, String> {

    List<LedgerAccount> findAllByOrderByCodeAsc();
}
