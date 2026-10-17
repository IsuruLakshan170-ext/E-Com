package com.ecom.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.ecom.audit.AuditAction;
import com.ecom.audit.AuditService;
import com.ecom.security.ActorType;
import com.ecom.user.AuthService;
import com.ecom.user.PasswordPolicy;
import com.ecom.user.Role;
import com.ecom.user.User;
import com.ecom.user.UserRepository;

/** Creates the first admin from env vars, once. There is no public endpoint that can grant ADMIN. */
@Component
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final AppProperties props;
    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final AuditService audit;

    public AdminBootstrap(AppProperties props, UserRepository users, PasswordEncoder passwordEncoder, AuditService audit) {
        this.props = props;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.audit = audit;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        AppProperties.Bootstrap bootstrap = props.bootstrap();
        if (bootstrap == null || isBlank(bootstrap.adminEmail()) || isBlank(bootstrap.adminPassword())) {
            return;
        }
        if (users.existsByRole(Role.ADMIN)) {
            return;
        }
        String email = AuthService.normalizeEmail(bootstrap.adminEmail());
        if (users.existsByEmail(email)) {
            log.warn("Bootstrap admin skipped: a non-admin account already uses {}", email);
            return;
        }
        PasswordPolicy.validate(bootstrap.adminPassword(), email);
        User admin = users.save(new User(email, passwordEncoder.encode(bootstrap.adminPassword()), "Administrator", Role.ADMIN));
        audit.recordAs(null, ActorType.SYSTEM, null, AuditAction.ADMIN_BOOTSTRAPPED, "user", admin.getId(), null);
        log.info("Bootstrap admin created for {}", email);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
