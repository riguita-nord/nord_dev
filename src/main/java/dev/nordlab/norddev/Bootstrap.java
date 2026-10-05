package dev.nordlab.norddev;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration class Bootstrap {
    @Bean CommandLineRunner initializePlatform(PlatformSettingsRepository settings, AccountRepository users) {
        return args -> {
            PlatformSettings state = settings.findById("global").orElseGet(() -> {
                PlatformSettings initial = new PlatformSettings();
                return settings.saveAndFlush(initial);
            });
            // Upgrade installations created with the previous environment-based admin bootstrap.
            if (state.ownerId == null) {
                Account legacyAdmin = users.findAllByOrderByCreatedAtAsc().stream()
                    .filter(account -> "ADMIN".equals(account.role)).findFirst().orElse(null);
                if (legacyAdmin != null) {
                    legacyAdmin.role = "OWNER";
                    users.save(legacyAdmin);
                    state.ownerId = legacyAdmin.id;
                    state.setupComplete = true;
                    if (state.publicUrl == null || state.publicUrl.isBlank()) state.publicUrl = "http://localhost:8080";
                    settings.save(state);
                }
            }
        };
    }
}
