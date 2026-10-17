package com.ecom.it;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import com.ecom.apikey.AgentLimits;
import com.ecom.apikey.ApiKeyDtos.ApiKeyResponse;
import com.ecom.apikey.ApiKeyDtos.CreateApiKeyRequest;
import com.ecom.apikey.ApiKeyService;
import com.ecom.cart.CartDtos.AddCartItemRequest;
import com.ecom.cart.CartService;
import com.ecom.catalog.CatalogDtos.CreateProductRequest;
import com.ecom.catalog.CatalogService;
import com.ecom.catalog.ProductRepository;
import com.ecom.common.error.ApiException;
import com.ecom.common.error.ErrorCode;
import com.ecom.finance.FinanceDtos.PaymentResponse;
import com.ecom.finance.FinanceDtos.RecordPaymentRequest;
import com.ecom.finance.FinanceService;
import com.ecom.finance.LedgerService;
import com.ecom.finance.PaymentMethod;
import com.ecom.inventory.InventoryService;
import com.ecom.order.OrderDtos.CheckoutRequest;
import com.ecom.order.OrderDtos.OrderResponse;
import com.ecom.order.OrderDtos.ShippingAddressDto;
import com.ecom.order.OrderRepository;
import com.ecom.order.OrderService;
import com.ecom.order.OrderStatus;
import com.ecom.purchasing.PurchasingDtos.GoodsReceiptLineRequest;
import com.ecom.purchasing.PurchasingDtos.GoodsReceiptRequest;
import com.ecom.purchasing.PurchasingDtos.PurchaseOrderLineRequest;
import com.ecom.purchasing.PurchasingDtos.PurchaseOrderRequest;
import com.ecom.purchasing.PurchasingDtos.SupplierRequest;
import com.ecom.purchasing.PurchasingService;
import com.ecom.purchasing.SupplierService;
import com.ecom.returns.ReturnDtos.CreateReturnRequest;
import com.ecom.returns.ReturnDtos.ReturnLineRequest;
import com.ecom.returns.ReturnService;
import com.ecom.security.ActorType;
import com.ecom.security.ApiKeyAuthentication;
import com.ecom.security.AuthenticatedPrincipal;
import com.ecom.security.SystemActor;
import com.ecom.user.Role;
import com.ecom.user.User;
import com.ecom.user.UserRepository;

/**
 * Shared harness for the Testcontainers integration tests (Phase 2.2). Tests call the services directly with a
 * chosen actor in the security context (services identify the actor via CurrentUser; scopes are checked by the
 * controllers, which are not under test here). One PostgreSQL container is shared by every IT class (singleton
 * pattern, so Spring's cached contexts keep a live database). Data is never cleaned up - the ledgers are
 * append-only - so every test creates its own users, SKUs and orders and asserts on those.
 */
@SpringBootTest(properties = {
        "DB_PASSWORD=unused-testcontainers-provides-it",
        "app.security.jwt.secret=integration-test-secret-integration-test-secret-0123",
        "app.jobs.enabled=false"
})
public abstract class IntegrationTestBase {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @Autowired protected UserRepository users;
    @Autowired protected ApiKeyService apiKeys;
    @Autowired protected CatalogService catalog;
    @Autowired protected CartService carts;
    @Autowired protected OrderService orders;
    @Autowired protected OrderRepository orderRepository;
    @Autowired protected FinanceService finance;
    @Autowired protected LedgerService ledger;
    @Autowired protected InventoryService inventory;
    @Autowired protected ProductRepository productRepository;
    @Autowired protected SupplierService suppliers;
    @Autowired protected PurchasingService purchasing;
    @Autowired protected ReturnService returns;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected TransactionTemplate tx;

    // ---------- actors ----------

    protected User user(Role role) {
        return users.save(new User("it-" + UUID.randomUUID() + "@example.com", "{noop}unused", "IT " + role, role));
    }

    /** Runs work as the given user (a JWT principal with the role's scopes) on the current thread. */
    protected <T> T as(User user, Supplier<T> work) {
        Jwt jwt = Jwt.withTokenValue("it-token").header("alg", "none").subject(user.getId().toString())
                .claim("scope", String.join(" ", user.getRole().scopes())).build();
        List<GrantedAuthority> authorities = user.getRole().scopes().stream()
                .map(s -> (GrantedAuthority) new SimpleGrantedAuthority("SCOPE_" + s)).toList();
        SecurityContext previous = SecurityContextHolder.getContext();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new JwtAuthenticationToken(jwt, authorities));
        SecurityContextHolder.setContext(context);
        try {
            return work.get();
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }

    /**
     * Creates a real API key for the owner, via the same service a human uses, with the given scopes and
     * spending guardrails (backend/REVIEW.md §5.3). Returns its id; the raw key material is never needed in
     * these tests since {@link #asApiKey} authenticates directly as the key, the way ApiKeyAuthFilter would
     * after verifying one.
     */
    protected UUID apiKeyFor(User owner, Set<String> scopes, AgentLimits limits) {
        CreateApiKeyRequest request = new CreateApiKeyRequest("IT agent key", scopes, 30,
                limits.maxOrderValueMinor(), limits.maxOrdersPerDay(), limits.requireApprovalAboveMinor());
        ApiKeyResponse created = as(owner, () -> apiKeys.create(owner.getId(), UUID.randomUUID().toString(), request)
                .response().key());
        return created.id();
    }

    /**
     * Runs work authenticated as one API key, the same effective-scopes computation the real filter chain uses
     * (key scopes INTERSECT owner's current role scopes) — this is what lets {@link
     * com.ecom.order.AgentOrderPolicy} see {@code ActorType.API_KEY}, which {@link #as} (always a JWT user)
     * never produces.
     */
    protected <T> T asApiKey(User owner, UUID apiKeyId, Set<String> scopes, Supplier<T> work) {
        Set<String> effective = new TreeSet<>(scopes); // ApiKeyAuthentication prefixes these to SCOPE_* itself
        effective.retainAll(owner.getRole().scopes());
        ApiKeyAuthentication authentication = new ApiKeyAuthentication(
                new AuthenticatedPrincipal(owner.getId(), ActorType.API_KEY, apiKeyId), effective);
        SecurityContext previous = SecurityContextHolder.getContext();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        try {
            return work.get();
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }

    // ---------- catalog, cart, orders ----------

    protected static String code(String prefix) {
        return prefix + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    protected UUID product(User admin, int initialStock, long unitCostMinor, long priceMinor) {
        return as(admin, () -> catalog.createProduct(new CreateProductRequest(code("IT-"), "IT product", null, null,
                priceMinor, initialStock, unitCostMinor, null, true))).id();
    }

    protected static ShippingAddressDto address() {
        return new ShippingAddressDto("IT Buyer", "1 Test Street", null, "Testville", null, "12345", "US");
    }

    protected void addToCart(User customer, UUID productId, int quantity) {
        as(customer, () -> carts.addItem(customer.getId(), new AddCartItemRequest(productId, quantity)));
    }

    protected long quotedTotal(User customer) {
        return as(customer, () -> carts.quote(customer.getId(), "US").total().amountMinor());
    }

    protected OrderResponse checkout(User customer) {
        long total = quotedTotal(customer);
        return as(customer, () -> orders.checkout(customer.getId(), UUID.randomUUID().toString(),
                new CheckoutRequest(address(), total)).order());
    }

    protected OrderResponse placeOrder(User customer, UUID productId, int quantity) {
        addToCart(customer, productId, quantity);
        return checkout(customer);
    }

    protected PaymentResponse pay(User financeUser, UUID orderId) {
        long total = orderRepository.findById(orderId).orElseThrow().getTotalMinor();
        return as(financeUser, () -> finance.recordPayment(orderId,
                new RecordPaymentRequest(PaymentMethod.CARD, total, "it")));
    }

    /** Place -> pay (financeUser) -> ship -> deliver (fulfilment); returns the order id. */
    protected UUID deliveredOrder(User buyer, User financeUser, User fulfilment, UUID productId, int quantity) {
        UUID orderId = placeOrder(buyer, productId, quantity).id();
        pay(financeUser, orderId);
        as(fulfilment, () -> orders.updateStatus(orderId, OrderStatus.SHIPPED));
        as(fulfilment, () -> orders.updateStatus(orderId, OrderStatus.DELIVERED));
        return orderId;
    }

    protected UUID requestReturn(User buyer, UUID orderId, UUID productId, int quantity) {
        return as(buyer, () -> returns.request(buyer.getId(), orderId, UUID.randomUUID().toString(),
                new CreateReturnRequest("Not as described", List.of(new ReturnLineRequest(productId, quantity)))))
                .returnRequest().id();
    }

    /** Pushes an unpaid order past its payment deadline (orders are not append-only). */
    protected void makeOverdue(UUID orderId) {
        jdbc.update("update orders set expires_at = now() - interval '1 minute' where id = ?", orderId);
    }

    protected boolean expire(UUID orderId) {
        return SystemActor.call(() -> orders.expireIfOverdue(orderId));
    }

    protected int reserved(UUID productId) {
        return productRepository.findById(productId).orElseThrow().getReserved();
    }

    protected int onHand(UUID productId) {
        return productRepository.findById(productId).orElseThrow().getStock();
    }

    // ---------- purchasing ----------

    protected UUID supplier(User purchaser) {
        return as(purchaser, () -> suppliers.create(new SupplierRequest(code("SUP-"), "IT supplier", null, null, null,
                30, true))).id();
    }

    /** Draft + submit as the purchaser; returns the PO id. */
    protected UUID submittedPo(User purchaser, UUID productId, int quantity, long unitCostMinor) {
        UUID supplierId = supplier(purchaser);
        UUID poId = as(purchaser, () -> purchasing.create(UUID.randomUUID().toString(),
                new PurchaseOrderRequest(supplierId, null, null,
                        List.of(new PurchaseOrderLineRequest(productId, quantity, unitCostMinor))))).purchaseOrder().id();
        as(purchaser, () -> purchasing.submit(poId));
        return poId;
    }

    protected void receive(User receiver, UUID poId, UUID productId, int quantity, String sodOverrideReason) {
        as(receiver, () -> purchasing.receive(poId, UUID.randomUUID().toString(),
                new GoodsReceiptRequest(List.of(new GoodsReceiptLineRequest(productId, quantity)), null, sodOverrideReason)));
    }

    /** Full strict-mode procure-to-stock with three different people. */
    protected UUID buyStock(User purchaser, User approver, User receiver, UUID productId, int quantity, long unitCostMinor) {
        UUID poId = submittedPo(purchaser, productId, quantity, unitCostMinor);
        as(approver, () -> purchasing.approve(poId, null));
        receive(receiver, poId, productId, quantity, null);
        return poId;
    }

    // ---------- finance ----------

    protected long glBalance(String accountCode) {
        return ledger.trialBalance().accounts().stream()
                .filter(a -> a.accountCode().equals(accountCode)).findFirst().orElseThrow().balanceMinor();
    }

    protected long inventoryValuation() {
        return inventory.valuation(0, 1).totalValue().amountMinor();
    }

    /** Net cash (GL 1000 debit - credit) of the journal entries posted for the given source documents. */
    protected long cashNet(UUID... sourceIds) {
        long net = 0;
        for (UUID id : sourceIds) {
            Long part = jdbc.queryForObject("select coalesce(sum(l.debit_minor - l.credit_minor), 0) from journal_lines l "
                    + "join journal_entries e on e.id = l.entry_id where l.account_code = '1000' and e.source_id = ?",
                    Long.class, id);
            net += part == null ? 0 : part;
        }
        return net;
    }

    protected int auditCount(String action, UUID targetId) {
        Integer n = jdbc.queryForObject("select count(*) from audit_events where action = ? and target_id = ?",
                Integer.class, action, targetId.toString());
        return n == null ? 0 : n;
    }

    // ---------- concurrency ----------

    /** Starts all tasks at the same moment; each result is the task's return value or the Throwable it threw. */
    protected static List<Object> race(List<Callable<Object>> tasks) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<Object> task : tasks) {
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        return task.call();
                    } catch (Throwable t) {
                        return t;
                    }
                }));
            }
            start.countDown();
            List<Object> results = new ArrayList<>();
            for (Future<Object> f : futures) {
                try {
                    results.add(f.get(90, TimeUnit.SECONDS));
                } catch (Exception e) {
                    results.add(e);
                }
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    protected static boolean isApiError(Object result, ErrorCode... codes) {
        return result instanceof ApiException e && Arrays.asList(codes).contains(e.code());
    }

    /** Deadlock losers / lock timeouts: R1 says these must not happen in normal flows. */
    protected static boolean isLockFailure(Object result) {
        return result instanceof PessimisticLockingFailureException;
    }

    protected static void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch timeout");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    protected static final Duration LOCK_TIMEOUT = Duration.ofSeconds(5);
}
