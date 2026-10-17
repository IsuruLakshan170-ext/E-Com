package com.ecom.common.docs;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

import org.springframework.stereotype.Service;

import com.ecom.common.util.Hashing;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * Document numbers. Accounting documents (invoices, credit notes, journal entries) use gap-tolerant
 * database sequences so they are strictly increasing, as auditors expect. Operational documents
 * (orders, POs, receipts, RMAs) use random suffixes so they can't be guessed.
 */
@Service
public class DocumentNumberService {

    public enum Sequence {
        INVOICE("invoice_number_seq", "INV-"),
        CREDIT_NOTE("credit_note_number_seq", "CN-"),
        JOURNAL_ENTRY("journal_entry_number_seq", "JE-");

        private final String sequenceName;
        private final String prefix;

        Sequence(String sequenceName, String prefix) {
            this.sequenceName = sequenceName;
            this.prefix = prefix;
        }
    }

    @PersistenceContext
    private EntityManager entityManager;

    /** Sequence names come only from the enum above, never from input. */
    public String next(Sequence sequence) {
        Number value = (Number) entityManager
                .createNativeQuery("select nextval('" + sequence.sequenceName + "')")
                .getSingleResult();
        return sequence.prefix + String.format("%08d", value.longValue());
    }

    public static String randomNumber(String prefix) {
        return prefix + LocalDate.now(ZoneOffset.UTC).format(DateTimeFormatter.BASIC_ISO_DATE)
                + "-" + Hashing.randomAlphanumeric(8);
    }
}
