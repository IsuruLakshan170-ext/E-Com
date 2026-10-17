package com.ecom.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

import com.ecom.apikey.ApiKeyRepository;
import com.ecom.audit.AuditAction;
import com.ecom.audit.AuditService;
import com.ecom.cart.Cart;
import com.ecom.cart.CartRepository;
import com.ecom.catalog.StockTotals;
import com.ecom.common.docs.DocumentNumberService;
import com.ecom.common.idempotency.IdempotencyRecordRepository;
import com.ecom.common.jobs.JobLocks;
import com.ecom.finance.AmountTotals;
import com.ecom.finance.InvoiceRepository;
import com.ecom.finance.JournalEntryRepository;
import com.ecom.finance.RefundRepository;
import com.ecom.purchasing.PurchaseOrderLineRepository;
import com.ecom.purchasing.PurchaseOrderStatus;
import com.ecom.returns.ReturnRequest;
import com.ecom.returns.ReturnRequestRepository;
import com.ecom.security.ActorType;
import com.ecom.user.RefreshTokenRepository;
import com.ecom.user.RevokeReason;
import com.ecom.user.Role;
import com.ecom.user.User;

/**
 * Review item (c): every query whose typing is only checked at boot or first call runs at least once against
 * real PostgreSQL: JPQL constructor expressions, casts and projections, native queries, advisory locks,
 * sequences and the R8 audit throttle.
 */
class RepositoryQueriesIT extends IntegrationTestBase {

    @Autowired private JobLocks jobLocks;
    @Autowired private IdempotencyRecordRepository idempotencyRecords;
    @Autowired private RefreshTokenRepository refreshTokens;
    @Autowired private ApiKeyRepository apiKeys;
    @Autowired private DocumentNumberService documentNumbers;
    @Autowired private JournalEntryRepository journal;
    @Autowired private InvoiceRepository invoices;
    @Autowired private RefundRepository refunds;
    @Autowired private PurchaseOrderLineRepository purchaseOrderLines;
    @Autowired private ReturnRequestRepository returnRequests;
    @Autowired private CartRepository cartRepository;
    @Autowired private AuditService audit;

    @Test
    void advisoryJobLockReturnsABoolean() {
        // typed locals: assertThat(tx.execute(...)) is ambiguous between the Predicate overloads
        Boolean locked = tx.execute(s -> jobLocks.tryLock(JobLocks.REFRESH_TOKEN_CLEANUP));
        assertThat(locked).isTrue();
    }

    @Test
    void modifyingQueriesRun() {
        tx.executeWithoutResult(s -> {
            idempotencyRecords.deleteCreatedBefore(Instant.EPOCH);
            refreshTokens.deleteExpiredBefore(Instant.EPOCH);
            refreshTokens.revokeAllForUser(UUID.randomUUID(), Instant.now(), RevokeReason.LOGOUT);
            apiKeys.revokeAllForUser(UUID.randomUUID(), Instant.now());
        });
    }

    @Test
    void constructorExpressionsAndProjectionsRun() {
        StockTotals totals = productRepository.stockTotals();
        assertThat(totals.units()).isNotNull();
        assertThat(totals.valueMinor()).isNotNull();
        assertThat(journal.totalsByAccount()).isNotNull();
        Instant from = Instant.now().minus(Duration.ofDays(1));
        Instant to = Instant.now().plus(Duration.ofDays(1));
        AmountTotals invoiced = invoices.totalsBetween(from, to);
        assertThat(invoiced.count()).isNotNull();
        assertThat(invoiced.amountMinor()).isNotNull();
        AmountTotals refunded = refunds.totalsBetween(from, to);
        assertThat(refunded.taxMinor()).isNotNull();
        assertThat(purchaseOrderLines.outstandingByProduct(EnumSet.allOf(PurchaseOrderStatus.class))).isNotNull();
        assertThat(productRepository.findReorderCandidates(PageRequest.of(0, 5))).isNotNull();
        assertThat(productRepository.findAllReorderCandidates()).isNotNull();
        User someone = user(Role.CUSTOMER);
        assertThat(users.findTokenState(someone.getId())).hasValueSatisfying(s -> assertThat(s.enabled()).isTrue());
    }

    @Test
    void nativeOrderQueriesRun() {
        assertThat(orderRepository.findExpiredUnpaidIds(Instant.now(), 10)).isNotNull();
        Optional<UUID> claimed = tx.execute(s -> orderRepository.claimForExpiry(UUID.randomUUID(), Instant.now()));
        assertThat(claimed).isEmpty();
    }

    @Test
    void sequencesAndScalarQueriesRun() {
        String number = tx.execute(s -> documentNumbers.next(DocumentNumberService.Sequence.JOURNAL_ENTRY));
        assertThat(number).startsWith("JE-");
        assertThat(returnRequests.findOrderIdById(UUID.randomUUID())).isEmpty();
        Optional<Cart> cart = tx.execute(s -> cartRepository.lockByUserId(UUID.randomUUID()));
        assertThat(cart).isEmpty();
        Optional<ReturnRequest> rma = tx.execute(s -> returnRequests.lockById(UUID.randomUUID()));
        assertThat(rma).isEmpty();
    }

    @Test
    void auditSearchWithEveryFilterRuns() {
        assertThat(audit.search(new AuditService.AuditQuery(AuditAction.LOGIN_FAILED, UUID.randomUUID(), ActorType.USER,
                UUID.randomUUID(), "user", "x", Instant.EPOCH, Instant.now()), 0, 10).items()).isEmpty();
    }

    @Test
    void throttledSecurityAuditsWriteOneRowPerMinute() { // R8
        String marker = "it-" + UUID.randomUUID();
        for (int i = 0; i < 5; i++) {
            audit.recordThrottled(marker, AuditAction.ACCESS_DENIED, "endpoint", null, "marker=" + marker);
        }
        Integer rows = jdbc.queryForObject("select count(*) from audit_events where details like ?", Integer.class,
                "marker=" + marker + "%");
        assertThat(rows).isEqualTo(1);
    }
}
