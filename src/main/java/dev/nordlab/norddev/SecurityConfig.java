package dev.nordlab.norddev;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }
    @Bean SecurityFilterChain security(HttpSecurity http) throws Exception {
        return http.csrf(csrf -> csrf.ignoringRequestMatchers("/api/licenses/verify"))
            .authorizeHttpRequests(a -> a.requestMatchers("/", "/login", "/register", "/setup", "/setup/**", "/css/**", "/js/**", "/api/licenses/verify", "/api/updates/**").permitAll()
                .requestMatchers("/admin", "/admin/**").hasAnyRole("ADMIN", "OWNER").anyRequest().authenticated())
            .formLogin(f -> f.loginPage("/login").defaultSuccessUrl("/app", true).permitAll())
            .logout(l -> l.logoutSuccessUrl("/login?logout"))
            .sessionManagement(s -> s.sessionFixation(f -> f.migrateSession()).maximumSessions(3))
            .headers(h -> h.frameOptions(frame -> frame.sameOrigin()).contentSecurityPolicy(c -> c.policyDirectives("default-src 'self'; style-src 'self' 'unsafe-inline' https://fonts.googleapis.com; font-src 'self' https://fonts.gstatic.com; script-src 'self'; img-src 'self' data:; connect-src 'self'")))
            .build();
    }
}
