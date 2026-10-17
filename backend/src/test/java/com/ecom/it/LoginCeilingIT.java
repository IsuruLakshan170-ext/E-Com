package com.ecom.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.ecom.common.error.ApiException;
import com.ecom.common.error.ErrorCode;
import com.ecom.user.AuthDtos.LoginRequest;
import com.ecom.user.AuthDtos.RegisterRequest;
import com.ecom.user.AuthService;

/** R5: credential stuffing from many IPs against one account hits a per-account ceiling; the owner's IP still works. */
class LoginCeilingIT extends IntegrationTestBase {

    @Autowired
    private AuthService auth;

    @Test
    @Timeout(180)
    void manyIpsCannotKeepGuessingOneAccount() {
        String email = "it-" + UUID.randomUUID() + "@example.com";
        String password = "correct-horse-battery-staple";
        fromIp("192.168.1.10", () -> auth.register(new RegisterRequest(email, password, "IT Login")));
        fromIp("192.168.1.10", () -> auth.login(new LoginRequest(email, password))); // the owner's usual IP: trusted

        for (int i = 1; i <= 20; i++) { // application.yml: account-max-failures = 20
            String ip = "10.1.0." + i;
            String guess = "wrong-password-" + i;
            assertThatThrownBy(() -> fromIp(ip, () -> auth.login(new LoginRequest(email, guess))))
                    .isInstanceOf(ApiException.class);
        }

        // even the RIGHT password from a new IP is refused now, with the same generic error
        assertThatThrownBy(() -> fromIp("10.2.0.1", () -> auth.login(new LoginRequest(email, password))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_CREDENTIALS));
        // ...while the owner's trusted IP still works
        assertThat(fromIp("192.168.1.10", () -> auth.login(new LoginRequest(email, password))).accessToken()).isNotBlank();
        Integer ceilingAudits = jdbc.queryForObject("select count(*) from audit_events a join users u "
                + "on u.id::text = a.target_id where u.email = ? and a.action = 'LOGIN_ACCOUNT_CEILING_REACHED'",
                Integer.class, email);
        assertThat(ceilingAudits).isEqualTo(1);
    }

    private <T> T fromIp(String ip, Supplier<T> work) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(ip);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            return work.get();
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }
}
