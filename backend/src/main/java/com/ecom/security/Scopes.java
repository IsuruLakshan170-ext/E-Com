package com.ecom.security;

/**
 * Fine-grained permissions. Both user JWTs and API keys carry scopes; endpoints authorise on
 * scopes (not roles) so an API key handed to an MCP server can be limited to exactly what it needs.
 */
public final class Scopes {

    public static final String AUTHORITY_PREFIX = "SCOPE_";

    // storefront
    public static final String PROFILE_READ = "profile:read";
    public static final String CART_READ = "cart:read";
    public static final String CART_WRITE = "cart:write";
    public static final String ORDERS_READ = "orders:read";
    public static final String ORDERS_WRITE = "orders:write";

    // back office
    public static final String CATALOG_WRITE = "catalog:write";
    public static final String ORDERS_ADMIN = "orders:admin";
    public static final String RETURNS_MANAGE = "returns:manage";
    public static final String INVENTORY_READ = "inventory:read";
    public static final String INVENTORY_WRITE = "inventory:write";
    public static final String PURCHASING_READ = "purchasing:read";
    public static final String PURCHASING_WRITE = "purchasing:write";
    public static final String PURCHASING_APPROVE = "purchasing:approve";
    public static final String FINANCE_READ = "finance:read";
    public static final String FINANCE_WRITE = "finance:write";
    public static final String AUDIT_READ = "audit:read";
    public static final String USERS_ADMIN = "users:admin";

    private Scopes() {
    }
}
