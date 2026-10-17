package com.ecom.security;

import jakarta.validation.constraints.Size;

/** R10: optional body for workflow steps that small-team mode lets one person do with a reason. */
public record SodOverrideRequest(@Size(max = SodPolicy.MAX_REASON_LENGTH) String sodOverrideReason) {
}
