package com.ecom.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.ecom.audit.AuditAction;
import com.ecom.audit.AuditService;
import com.ecom.common.error.ApiException;
import com.ecom.common.error.ErrorCode;
import com.ecom.config.AppProperties;
import com.ecom.config.SodMode;

/** R10: strict vs small-team separation-of-duties policy. */
class SodPolicyTest {

    private final AuditService audit = mock(AuditService.class);
    private final UUID poId = UUID.randomUUID();

    private SodPolicy policy(SodMode mode) {
        return new SodPolicy(new AppProperties("USD", null, null, mode == null ? null : new AppProperties.Sod(mode), null), audit);
    }

    @Test
    void defaultModeIsStrict() {
        assertThat(policy(null).mode()).isEqualTo(SodMode.STRICT);
    }

    @Test
    void strictModeEnforcesTheRuleEvenWithAReason() {
        assertThatThrownBy(() -> policy(SodMode.STRICT).enforce(true, "approvePurchaseOrder",
                ErrorCode.SELF_APPROVAL_NOT_ALLOWED, "rule", "I am the only buyer", "purchase_order", poId))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.SELF_APPROVAL_NOT_ALLOWED));
        verifyNoInteractions(audit);
    }

    @Test
    void smallTeamModeNeedsAReason() {
        assertThatThrownBy(() -> policy(SodMode.SMALL_TEAM).enforce(true, "approvePurchaseOrder",
                ErrorCode.SELF_APPROVAL_NOT_ALLOWED, "rule", "  ", "purchase_order", poId))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.SEGREGATION_OF_DUTIES);
                    assertThat(e.details()).containsEntry("overrideAllowed", true);
                    assertThat(e.getMessage()).contains("sodOverrideReason");
                });
    }

    @Test
    void smallTeamModeAllowsAndAuditsAnOverride() {
        policy(SodMode.SMALL_TEAM).enforce(true, "approvePurchaseOrder", ErrorCode.SELF_APPROVAL_NOT_ALLOWED,
                "rule", "Only one person works here", "purchase_order", poId);
        verify(audit).record(eq(AuditAction.SOD_OVERRIDDEN), eq("purchase_order"), any(),
                contains("reason=Only one person works here"));
    }

    @Test
    void noConflictIsANoOpInEveryMode() {
        policy(SodMode.STRICT).enforce(false, "s", ErrorCode.SEGREGATION_OF_DUTIES, "rule", null, "t", poId);
        policy(SodMode.SMALL_TEAM).enforce(false, "s", ErrorCode.SEGREGATION_OF_DUTIES, "rule", null, "t", poId);
        verifyNoInteractions(audit);
    }
}
