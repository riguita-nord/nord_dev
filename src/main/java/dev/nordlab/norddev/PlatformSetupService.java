package dev.nordlab.norddev;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.Locale;

@Service
class PlatformSetupService {
    private final AccountRepository users;
    private final PlatformSettingsRepository settings;
    private final PasswordEncoder passwords;

    PlatformSetupService(AccountRepository users, PlatformSettingsRepository settings, PasswordEncoder passwords) {
        this.users = users;
        this.settings = settings;
        this.passwords = passwords;
    }

    @Transactional
    Account createFirstOwner(String email, String password, String platformName) {
        PlatformSettings state = settings.lockGlobal("global").orElseThrow();
        if (state.setupComplete || state.ownerId != null || users.count() != 0) {
            throw new IllegalStateException("Initial platform setup has already started.");
        }
        Account owner = users.saveAndFlush(new Account(email.trim().toLowerCase(Locale.ROOT), passwords.encode(password), "OWNER"));
        state.ownerId = owner.id;
        state.name = platformName.trim();
        settings.save(state);
        return owner;
    }

    @Transactional
    void finish(String actorEmail, String name, String publicUrl, String timezone) {
        PlatformSettings state = settings.lockGlobal("global").orElseThrow();
        Account owner = users.findById(state.ownerId == null ? -1L : state.ownerId).orElseThrow();
        if (!"OWNER".equals(owner.role) || !owner.email.equalsIgnoreCase(actorEmail)) {
            throw new SecurityException("Only the Platform Owner can finish setup.");
        }
        if (state.setupComplete) throw new IllegalStateException("Platform setup is already complete.");
        state.name = name.trim();
        state.publicUrl = publicUrl.replaceAll("/+$", "");
        state.timezone = timezone;
        state.setupComplete = true;
        state.configuredAt = Instant.now();
        settings.save(state);
    }
}
