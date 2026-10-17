package com.ecom.cart;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ecom.cart.CartDtos.AddCartItemRequest;
import com.ecom.cart.CartDtos.CartLine;
import com.ecom.cart.CartDtos.CartQuoteResponse;
import com.ecom.cart.CartDtos.CartResponse;
import com.ecom.catalog.Product;
import com.ecom.catalog.ProductRepository;
import com.ecom.common.error.ApiException;
import com.ecom.common.error.ErrorCode;
import com.ecom.common.web.Money;
import com.ecom.config.AppProperties;
import com.ecom.tax.TaxService;

@Service
public class CartService {

    static final int MAX_LINE_QUANTITY = 100;
    static final int MAX_LINES = 50;

    private final CartRepository carts;
    private final ProductRepository products;
    private final TaxService taxService;
    private final String currency;

    public CartService(CartRepository carts, ProductRepository products, TaxService taxService, AppProperties props) {
        this.carts = carts;
        this.products = products;
        this.taxService = taxService;
        this.currency = props.currency();
    }

    @Transactional(readOnly = true)
    public CartQuoteResponse quote(UUID userId, String countryCode) {
        CartResponse cart = view(userId);
        int rate = taxService.rateBasisPointsFor(countryCode);
        long subtotal = cart.subtotal().amountMinor();
        long tax = TaxService.taxFor(subtotal, rate);
        return new CartQuoteResponse(countryCode, cart.subtotal(), rate, Money.of(tax, currency),
                Money.of(Math.addExact(subtotal, tax), currency), cart.readyForCheckout());
    }

    @Transactional(readOnly = true)
    public CartResponse view(UUID userId) {
        return carts.findByUserId(userId).map(this::toResponse).orElseGet(this::emptyCart);
    }

    @Transactional
    public CartResponse addItem(UUID userId, AddCartItemRequest request) {
        Product product = activeProduct(request.productId());
        Cart cart = carts.findByUserId(userId).orElseGet(() -> carts.save(new Cart(userId)));

        int existing = cart.findItem(product.getId()).map(CartItem::getQuantity).orElse(0);
        if (existing == 0 && cart.getItems().size() >= MAX_LINES) {
            throw new ApiException(ErrorCode.LIMIT_EXCEEDED, "A cart can hold at most " + MAX_LINES + " different products");
        }
        int newQuantity = existing + request.quantity();
        checkQuantity(product, newQuantity);
        cart.putItem(product.getId(), newQuantity);
        return toResponse(cart);
    }

    @Transactional
    public CartResponse setQuantity(UUID userId, UUID productId, int quantity) {
        Cart cart = carts.findByUserId(userId).orElseThrow(() -> ApiException.notFound("Cart item"));
        if (cart.findItem(productId).isEmpty()) {
            throw ApiException.notFound("Cart item");
        }
        if (quantity == 0) {
            cart.removeItem(productId);
        } else {
            checkQuantity(activeProduct(productId), quantity);
            cart.putItem(productId, quantity);
        }
        return toResponse(cart);
    }

    @Transactional
    public CartResponse removeItem(UUID userId, UUID productId) {
        Cart cart = carts.findByUserId(userId).orElseThrow(() -> ApiException.notFound("Cart item"));
        if (cart.findItem(productId).isEmpty()) {
            throw ApiException.notFound("Cart item");
        }
        cart.removeItem(productId);
        return toResponse(cart);
    }

    @Transactional
    public void clear(UUID userId) {
        carts.findByUserId(userId).ifPresent(Cart::clear);
    }

    private Product activeProduct(UUID productId) {
        return products.findById(productId).filter(Product::isActive)
                .orElseThrow(() -> new ApiException(ErrorCode.PRODUCT_UNAVAILABLE, "Product is not available",
                        Map.of("productId", productId)));
    }

    /** Soft check only; the authoritative stock check happens under row locks at checkout. */
    private static void checkQuantity(Product product, int quantity) {
        if (quantity > MAX_LINE_QUANTITY) {
            throw new ApiException(ErrorCode.LIMIT_EXCEEDED, "At most " + MAX_LINE_QUANTITY + " units per product");
        }
        if (quantity > product.getAvailable()) {
            throw new ApiException(ErrorCode.INSUFFICIENT_STOCK, "Not enough stock",
                    Map.of("productId", product.getId(), "requested", quantity, "available", product.getAvailable()));
        }
    }

    private CartResponse toResponse(Cart cart) {
        Map<UUID, Product> byId = products.findAllById(cart.productIds()).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        List<CartLine> lines = new ArrayList<>();
        long subtotal = 0;
        int totalQuantity = 0;
        boolean ready = !cart.getItems().isEmpty();

        for (CartItem item : cart.getItems()) {
            Product p = byId.get(item.getProductId());
            totalQuantity += item.getQuantity();
            if (p == null || !p.isActive()) {
                ready = false;
                lines.add(new CartLine(item.getProductId(), p == null ? null : p.getSku(), p == null ? null : p.getName(),
                        null, item.getQuantity(), null, 0, ErrorCode.PRODUCT_UNAVAILABLE.name()));
                continue;
            }
            long lineTotal = Math.multiplyExact(p.getPriceMinor(), item.getQuantity());
            subtotal = Math.addExact(subtotal, lineTotal);
            String issue = p.getAvailable() < item.getQuantity() ? ErrorCode.INSUFFICIENT_STOCK.name() : null;
            if (issue != null) {
                ready = false;
            }
            lines.add(new CartLine(p.getId(), p.getSku(), p.getName(), Money.of(p.getPriceMinor(), p.getCurrency()),
                    item.getQuantity(), Money.of(lineTotal, p.getCurrency()), p.getAvailable(), issue));
        }
        return new CartResponse(lines, totalQuantity, Money.of(subtotal, currency), ready, cart.getUpdatedAt());
    }

    private CartResponse emptyCart() {
        return new CartResponse(List.of(), 0, Money.of(0, currency), false, null);
    }
}
