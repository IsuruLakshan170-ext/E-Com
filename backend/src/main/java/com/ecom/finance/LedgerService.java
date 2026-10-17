package com.ecom.finance;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ecom.common.docs.DocumentNumberService;
import com.ecom.common.web.PageResponse;
import com.ecom.finance.FinanceDtos.JournalEntryResponse;
import com.ecom.finance.FinanceDtos.JournalLineResponse;
import com.ecom.finance.FinanceDtos.LedgerAccountResponse;
import com.ecom.finance.FinanceDtos.TrialBalanceLine;
import com.ecom.finance.FinanceDtos.TrialBalanceResponse;
import com.ecom.security.AuthenticatedPrincipal;
import com.ecom.security.CurrentUser;

/**
 * Double-entry general ledger. Business services call {@link #post} inside their own transaction,
 * so an operational change and its accounting entry commit or roll back together.
 */
@Service
public class LedgerService {

    public record Posting(String accountCode, long debitMinor, long creditMinor) {
        public static Posting debit(String accountCode, long amountMinor) {
            return new Posting(accountCode, amountMinor, 0);
        }

        public static Posting credit(String accountCode, long amountMinor) {
            return new Posting(accountCode, 0, amountMinor);
        }
    }

    private final JournalEntryRepository entries;
    private final LedgerAccountRepository accounts;
    private final DocumentNumberService documentNumbers;

    public LedgerService(JournalEntryRepository entries, LedgerAccountRepository accounts,
                         DocumentNumberService documentNumbers) {
        this.entries = entries;
        this.accounts = accounts;
        this.documentNumbers = documentNumbers;
    }

    /**
     * Posts a balanced entry. Zero-amount lines are dropped; if nothing is left, nothing is posted.
     * An unbalanced entry is a programming error and aborts the whole business transaction.
     */
    @Transactional
    public Optional<JournalEntry> post(String description, String sourceType, UUID sourceId, List<Posting> postings) {
        List<Posting> lines = new ArrayList<>();
        long debits = 0;
        long credits = 0;
        for (Posting p : postings) {
            if (p.debitMinor() < 0 || p.creditMinor() < 0 || (p.debitMinor() > 0 && p.creditMinor() > 0)) {
                throw new IllegalStateException("Invalid posting " + p);
            }
            if (p.debitMinor() == 0 && p.creditMinor() == 0) {
                continue;
            }
            debits = Math.addExact(debits, p.debitMinor());
            credits = Math.addExact(credits, p.creditMinor());
            lines.add(p);
        }
        if (debits != credits) {
            throw new IllegalStateException("Unbalanced journal entry: debits=" + debits + " credits=" + credits);
        }
        if (lines.isEmpty()) {
            return Optional.empty();
        }
        JournalEntry entry = new JournalEntry(documentNumbers.next(DocumentNumberService.Sequence.JOURNAL_ENTRY),
                description, sourceType, sourceId, CurrentUser.find().map(AuthenticatedPrincipal::userId).orElse(null));
        lines.forEach(p -> entry.addLine(p.accountCode(), p.debitMinor(), p.creditMinor()));
        return Optional.of(entries.save(entry));
    }

    @Transactional(readOnly = true)
    public List<LedgerAccountResponse> listAccounts() {
        return accounts.findAllByOrderByCodeAsc().stream()
                .map(a -> new LedgerAccountResponse(a.getCode(), a.getName(), a.getType()))
                .toList();
    }

    @Transactional(readOnly = true)
    public PageResponse<JournalEntryResponse> listEntries(String sourceType, int page, int size) {
        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "postedAt").and(Sort.by("id")));
        return PageResponse.of((sourceType == null ? entries.findAll(pageable) : entries.findBySourceType(sourceType, pageable))
                .map(e -> new JournalEntryResponse(e.getId(), e.getEntryNumber(), e.getPostedAt(), e.getDescription(),
                        e.getSourceType(), e.getSourceId(), e.getCreatedBy(),
                        e.getLines().stream()
                                .map(l -> new JournalLineResponse(l.getAccountCode(), l.getDebitMinor(), l.getCreditMinor()))
                                .toList())));
    }

    @Transactional(readOnly = true)
    public TrialBalanceResponse trialBalance() {
        Map<String, AccountTotal> totals = entries.totalsByAccount().stream()
                .collect(Collectors.toMap(AccountTotal::accountCode, Function.identity()));
        List<TrialBalanceLine> lines = new ArrayList<>();
        long totalDebit = 0;
        long totalCredit = 0;
        for (LedgerAccount account : accounts.findAllByOrderByCodeAsc()) {
            AccountTotal t = totals.get(account.getCode());
            long debit = t == null ? 0 : t.debitMinor();
            long credit = t == null ? 0 : t.creditMinor();
            totalDebit += debit;
            totalCredit += credit;
            long balance = account.getType().debitNormal() ? debit - credit : credit - debit;
            lines.add(new TrialBalanceLine(account.getCode(), account.getName(), account.getType(), debit, credit, balance));
        }
        return new TrialBalanceResponse(lines, totalDebit, totalCredit, totalDebit == totalCredit);
    }
}
