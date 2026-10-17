package com.ecom.user;

import java.util.UUID;

/** Published inside the revoking transaction; the apikey module listens and revokes that user's keys. */
public record CredentialsRevokedEvent(UUID userId, String reason) {
}
