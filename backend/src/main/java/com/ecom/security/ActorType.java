package com.ecom.security;

public enum ActorType {
    /** Interactive user holding a JWT. */
    USER,
    /** Automated client (e.g. the MCP server) acting on behalf of a user via a scoped API key. */
    API_KEY,
    ANONYMOUS,
    SYSTEM
}
