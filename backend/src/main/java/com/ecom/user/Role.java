package com.ecom.user;

import static com.ecom.security.Scopes.*;

import java.util.List;

/**
 * Roles are bundles of scopes. Back-office roles follow least privilege and segregation of duties:
 * e.g. purchasing can raise and approve POs but not receive stock; finance records money but
 * cannot change stock.
 */
public enum Role {
    CUSTOMER(PROFILE_READ, CART_READ, CART_WRITE, ORDERS_READ, ORDERS_WRITE),
    CUSTOMER_SERVICE(PROFILE_READ, ORDERS_ADMIN, RETURNS_MANAGE),
    INVENTORY_MANAGER(PROFILE_READ, INVENTORY_READ, INVENTORY_WRITE, PURCHASING_READ),
    PURCHASING_MANAGER(PROFILE_READ, PURCHASING_READ, PURCHASING_WRITE, PURCHASING_APPROVE, INVENTORY_READ),
    FINANCE_MANAGER(PROFILE_READ, FINANCE_READ, FINANCE_WRITE, PURCHASING_READ, INVENTORY_READ, AUDIT_READ),
    ADMIN(PROFILE_READ, CART_READ, CART_WRITE, ORDERS_READ, ORDERS_WRITE,
            CATALOG_WRITE, ORDERS_ADMIN, RETURNS_MANAGE, INVENTORY_READ, INVENTORY_WRITE,
            PURCHASING_READ, PURCHASING_WRITE, PURCHASING_APPROVE, FINANCE_READ, FINANCE_WRITE,
            AUDIT_READ, USERS_ADMIN);

    private final List<String> scopes;

    Role(String... scopes) {
        this.scopes = List.of(scopes);
    }

    /** The maximum set of scopes a holder of this role (or any API key they create) can have. */
    public List<String> scopes() {
        return scopes;
    }
}
