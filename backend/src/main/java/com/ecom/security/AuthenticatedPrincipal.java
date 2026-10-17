package com.ecom.security;

import java.util.UUID;

/** The resolved caller. For API keys, userId is the key owner and apiKeyId identifies the key. */
public record AuthenticatedPrincipal(UUID userId, ActorType actorType, UUID apiKeyId) {
}
