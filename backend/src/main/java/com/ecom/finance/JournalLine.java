package com.ecom.finance;

import java.util.UUID;

import org.hibernate.annotations.Immutable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Immutable
@Table(name = "journal_lines")
public class JournalLine {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "entry_id", nullable = false)
    private JournalEntry entry;

    @Column(name = "account_code", nullable = false, length = 10)
    private String accountCode;

    @Column(name = "debit_minor", nullable = false)
    private long debitMinor;

    @Column(name = "credit_minor", nullable = false)
    private long creditMinor;

    protected JournalLine() {
    }

    JournalLine(JournalEntry entry, String accountCode, long debitMinor, long creditMinor) {
        this.entry = entry;
        this.accountCode = accountCode;
        this.debitMinor = debitMinor;
        this.creditMinor = creditMinor;
    }

    public String getAccountCode() { return accountCode; }
    public long getDebitMinor() { return debitMinor; }
    public long getCreditMinor() { return creditMinor; }
}
