package com.ecom.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ecom.common.docs.DocumentNumberService;
import com.ecom.finance.LedgerService.Posting;

/** The ledger must only ever accept balanced, one-sided lines. */
class LedgerServiceTest {

    private JournalEntryRepository entries;
    private LedgerService ledger;

    @BeforeEach
    void setUp() {
        entries = mock(JournalEntryRepository.class);
        DocumentNumberService numbers = mock(DocumentNumberService.class);
        when(numbers.next(any())).thenReturn("JE-00000001");
        when(entries.save(any())).thenAnswer(inv -> inv.getArgument(0));
        ledger = new LedgerService(entries, mock(LedgerAccountRepository.class), numbers);
    }

    @Test
    void postsBalancedEntries() {
        var entry = ledger.post("Payment", "payment", UUID.randomUUID(), List.of(
                Posting.debit(LedgerAccounts.CASH, 5_500),
                Posting.credit(LedgerAccounts.CUSTOMER_DEPOSITS, 5_000),
                Posting.credit(LedgerAccounts.SALES_TAX_PAYABLE, 500)));
        assertThat(entry).isPresent();
        assertThat(entry.get().getLines()).hasSize(3);
    }

    @Test
    void rejectsUnbalancedEntries() {
        assertThatThrownBy(() -> ledger.post("Bad", "test", null, List.of(
                Posting.debit(LedgerAccounts.CASH, 100), Posting.credit(LedgerAccounts.SALES_REVENUE, 99))))
                .isInstanceOf(IllegalStateException.class);
        verify(entries, never()).save(any());
    }

    @Test
    void rejectsLinesWithBothSides() {
        assertThatThrownBy(() -> ledger.post("Bad", "test", null, List.of(
                new Posting(LedgerAccounts.CASH, 100, 100))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void allZeroEntriesAreSkipped() {
        assertThat(ledger.post("Free order", "order", null, List.of(
                Posting.debit(LedgerAccounts.COST_OF_GOODS_SOLD, 0), Posting.credit(LedgerAccounts.INVENTORY, 0))))
                .isEmpty();
        verify(entries, never()).save(any());
    }
}
