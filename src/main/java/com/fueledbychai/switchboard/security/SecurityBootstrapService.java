package com.fueledbychai.switchboard.security;

import com.fueledbychai.switchboard.config.SwitchboardSecurityProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;

@Component
public class SecurityBootstrapService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SecurityBootstrapService.class);
    private static final String DEFAULT_ROLE = "ADMIN";
    private static final char[] PASSWORD_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789".toCharArray();

    private final SwitchboardUserRepository switchboardUserRepository;
    private final SwitchboardSecurityProperties securityProperties;
    private final PasswordEncoder passwordEncoder;
    private final SecureRandom secureRandom = new SecureRandom();

    public SecurityBootstrapService(SwitchboardUserRepository switchboardUserRepository,
                                    SwitchboardSecurityProperties securityProperties,
                                    PasswordEncoder passwordEncoder) {
        this.switchboardUserRepository = switchboardUserRepository;
        this.securityProperties = securityProperties;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (switchboardUserRepository.count() > 0) {
            return;
        }

        String rawPassword = securityProperties.getBootstrapPassword();
        boolean generated = rawPassword == null || rawPassword.isBlank();
        if (generated) {
            rawPassword = generatePassword(20);
        }

        SwitchboardUserEntity user = new SwitchboardUserEntity();
        user.setUsername(securityProperties.getBootstrapUsername());
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        user.setRoleName(DEFAULT_ROLE);
        switchboardUserRepository.save(user);

        if (generated) {
            log.warn("Created bootstrap admin user '{}' with generated password: {}",
                    user.getUsername(), rawPassword);
            log.warn("Persist this password somewhere safe or set switchboard.security.bootstrap-password before first run.");
        } else {
            log.info("Created bootstrap admin user '{}'.", user.getUsername());
        }
    }

    private String generatePassword(int length) {
        StringBuilder builder = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            builder.append(PASSWORD_CHARS[secureRandom.nextInt(PASSWORD_CHARS.length)]);
        }
        return builder.toString();
    }
}
