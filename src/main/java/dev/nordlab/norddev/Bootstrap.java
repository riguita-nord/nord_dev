package dev.nordlab.norddev;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration class Bootstrap {
    @Bean CommandLineRunner ensureAdmin(AccountRepository users, PasswordEncoder encoder,
        @Value("${nord.bootstrap.admin-email:}") String email,
        @Value("${nord.bootstrap.admin-password:}") String password) {
        return args -> {
            if (email == null || email.isBlank() || password == null || password.length() < 14) return;
            users.findByEmailIgnoreCase(email.trim()).ifPresentOrElse(
                a -> { if (!"ADMIN".equals(a.role)) { a.role="ADMIN"; users.save(a); } },
                () -> users.save(new Account(email.trim().toLowerCase(),encoder.encode(password),"ADMIN")));
        };
    }
}
