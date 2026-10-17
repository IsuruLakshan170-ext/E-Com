package com.ecom.security;

import java.util.Map;

import org.springframework.stereotype.Component;

import com.ecom.audit.AuditAction;
import com.ecom.audit.AuditService;
import com.ecom.common.error.ApiException;
import com.ecom.common.error.ErrorCode;
import com.ecom.config.AppProperties;
import com.ecom.config.SodMode;

/**
 * R10: one place that applies a separation-of-duties rule for one workflow step.
 * <ul>
 *   <li>STRICT (default): the rule is enforced, so a different person must do the step.</li>
 *   <li>SMALL_TEAM: the same person may do the step, but only with a non-blank reason, and every override is
 *       audited as SOD_OVERRIDDEN (review them via listAuditEvents?action=SOD_OVERRIDDEN).</li>
 * </ul>
 * Conflict-of-interest rules (staff processing their OWN return, recording payment for their OWN order) do
 * not go through this policy: they are hard blocks in every mode.
 */
@Component
public class SodPolicy {

    public static final int MAX_REASON_LENGTH = 300;

    private final SodMode mode;
    private final AuditService audit;

    public SodPolicy(AppProperties props, AuditService audit) {
        this.mode = props.sod() == null || props.sod().mode() == null ? SodMode.STRICT : props.sod().mode();
        this.audit = audit;
    }

    public SodMode mode() {
        return mode;
    }

    /**
     * @param conflict       true if the actor already did a conflicting step on this document
     * @param step           operation name, e.g. "approvePurchaseOrder"
     * @param strictCode     error returned in STRICT mode
     * @param rule           human-readable rule, used in errors and the audit row
     * @param overrideReason the request's sodOverrideReason (may be null)
     */
    public void enforce(boolean conflict, String step, ErrorCode strictCode, String rule, String overrideReason,
                        String targetType, Object targetId) {
        if (!conflict) {
            return;
        }
        if (mode == SodMode.STRICT) {
            throw new ApiException(strictCode, rule, Map.of("step", step, "sodMode", "strict"));
        }
        if (overrideReason == null || overrideReason.isBlank()) {
            throw new ApiException(ErrorCode.SEGREGATION_OF_DUTIES,
                    rule + ". This shop runs in small-team mode: repeat the request with a sodOverrideReason "
                            + "(max " + MAX_REASON_LENGTH + " characters) to do this step yourself.",
                    Map.of("step", step, "sodMode", "small-team", "overrideAllowed", true));
        }
        audit.record(AuditAction.SOD_OVERRIDDEN, targetType, targetId,
                "step=" + step + ", rule=" + rule + ", reason=" + overrideReason.trim());
    }
}
