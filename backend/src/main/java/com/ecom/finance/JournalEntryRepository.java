package com.ecom.finance;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface JournalEntryRepository extends JpaRepository<JournalEntry, UUID> {

    Page<JournalEntry> findBySourceType(String sourceType, Pageable pageable);

    @Query("select new com.ecom.finance.AccountTotal(l.accountCode, sum(l.debitMinor), sum(l.creditMinor)) "
            + "from JournalLine l group by l.accountCode")
    List<AccountTotal> totalsByAccount();
}
