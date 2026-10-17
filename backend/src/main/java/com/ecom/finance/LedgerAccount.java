package com.ecom.finance;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Read-only chart of accounts; rows are created by migrations only. */
@Entity
@Table(name = "ledger_accounts")
public class LedgerAccount {

    @Id
    @Column(length = 10)
    private String code;

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AccountType type;

    protected LedgerAccount() {
    }

    public String getCode() { return code; }
    public String getName() { return name; }
    public AccountType getType() { return type; }
}
