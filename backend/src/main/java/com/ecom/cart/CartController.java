package com.ecom.cart;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.ecom.cart.CartDtos.AddCartItemRequest;
import com.ecom.cart.CartDtos.CartQuoteResponse;
import com.ecom.cart.CartDtos.CartResponse;
import com.ecom.cart.CartDtos.UpdateCartItemRequest;
import com.ecom.security.CurrentUser;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;

@RestController
@RequestMapping("/api/v1/cart")
@Tag(name = "Cart")
public class CartController {

    private final CartService cartService;

    public CartController(CartService cartService) {
        this.cartService = cartService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('SCOPE_cart:read')")
    @Operation(operationId = "getCart", summary = "Get the current user's cart with live prices and any stock issues")
    public CartResponse get() {
        return cartService.view(CurrentUser.id());
    }

    @GetMapping("/quote")
    @PreAuthorize("hasAuthority('SCOPE_cart:read')")
    @Operation(operationId = "quoteCart", summary = "Preview subtotal, tax and total for a destination country; send total.amountMinor to placeOrder as expectedTotalMinor")
    public CartQuoteResponse quote(@RequestParam @Pattern(regexp = "^[A-Z]{2}$") String countryCode) {
        return cartService.quote(CurrentUser.id(), countryCode);
    }

    @PostMapping("/items")
    @PreAuthorize("hasAuthority('SCOPE_cart:write')")
    @Operation(operationId = "addCartItem", summary = "Add a product to the cart (adds to the quantity if already present)")
    public CartResponse addItem(@Valid @RequestBody AddCartItemRequest request) {
        return cartService.addItem(CurrentUser.id(), request);
    }

    @PutMapping("/items/{productId}")
    @PreAuthorize("hasAuthority('SCOPE_cart:write')")
    @Operation(operationId = "setCartItemQuantity", summary = "Set the exact quantity of a cart line (0 removes it)")
    public CartResponse setQuantity(@PathVariable UUID productId, @Valid @RequestBody UpdateCartItemRequest request) {
        return cartService.setQuantity(CurrentUser.id(), productId, request.quantity());
    }

    @DeleteMapping("/items/{productId}")
    @PreAuthorize("hasAuthority('SCOPE_cart:write')")
    @Operation(operationId = "removeCartItem", summary = "Remove a product from the cart")
    public CartResponse removeItem(@PathVariable UUID productId) {
        return cartService.removeItem(CurrentUser.id(), productId);
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('SCOPE_cart:write')")
    @Operation(operationId = "clearCart", summary = "Remove everything from the cart")
    public void clear() {
        cartService.clear(CurrentUser.id());
    }
}
