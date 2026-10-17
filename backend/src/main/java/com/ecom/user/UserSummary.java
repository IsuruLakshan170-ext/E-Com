package com.ecom.user;

import java.util.UUID;

/** Who a customer is, for staff screens that show a name next to an id (BE-1). No credentials, role or state. */
public record UserSummary(UUID id, String email, String fullName) {
}
