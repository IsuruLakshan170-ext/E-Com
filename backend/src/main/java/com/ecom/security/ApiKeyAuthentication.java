package com.ecom.security;

import java.util.Collection;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

public class ApiKeyAuthentication extends AbstractAuthenticationToken {

    private final AuthenticatedPrincipal principal;

    public ApiKeyAuthentication(AuthenticatedPrincipal principal, Collection<String> scopes) {
        super(scopes.stream().map(s -> new SimpleGrantedAuthority(Scopes.AUTHORITY_PREFIX + s)).toList());
        this.principal = principal;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return null; // the raw key is never kept in memory after verification
    }

    @Override
    public AuthenticatedPrincipal getPrincipal() {
        return principal;
    }
}
