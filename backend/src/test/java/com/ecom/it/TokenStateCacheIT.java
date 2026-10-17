package com.ecom.it;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.ecom.security.UserTokenStateCache;
import com.ecom.user.Role;
import com.ecom.user.User;
import com.ecom.user.UserAdminService;

/** R9: re-enabling a user evicts the cached "disabled" state at commit, so their next token works at once. */
class TokenStateCacheIT extends IntegrationTestBase {

    @Autowired
    private UserAdminService userAdmin;

    @Autowired
    private UserTokenStateCache cache;

    @Test
    void reEnablingAUserTakesEffectImmediately() {
        User admin = user(Role.ADMIN);
        User member = user(Role.CUSTOMER);

        as(admin, () -> userAdmin.setEnabled(admin.getId(), member.getId(), false));
        assertThat(cache.get(member.getId())).hasValueSatisfying(s -> assertThat(s.enabled()).isFalse()); // now cached

        as(admin, () -> userAdmin.setEnabled(admin.getId(), member.getId(), true));
        assertThat(cache.get(member.getId())).hasValueSatisfying(s -> assertThat(s.enabled()).isTrue());  // not after 15 s
    }
}
