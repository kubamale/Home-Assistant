package dev.kuba.homedisplay.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

/**
 * Three credential kinds, one rule set: the phone (user) can do everything, the door screen
 * (display) can read, tick tasks and toggle lights, the agent can only open its WebSocket.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /** Logins go through our own token filter. An empty user store stops Spring generating a default password. */
    @Bean
    UserDetailsService noUsers() {
        return new InMemoryUserDetailsManager();
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, CredentialService credentials) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable) // cookie is SameSite=Lax and OriginCheckFilter guards writes
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(Customizer.withDefaults())
                .addFilterBefore(new TokenAuthFilter(credentials), AuthorizationFilter.class)
                .addFilterBefore(new OriginCheckFilter(), TokenAuthFilter.class)
                .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/error").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/login", "/api/auth/logout",
                                "/api/auth/pair/start").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/auth/pair/poll", "/api/health").permitAll()
                        .requestMatchers("/agent/ws").hasRole("AGENT")
                        .requestMatchers(HttpMethod.PATCH, "/api/tasks/*").hasAnyRole("USER", "DISPLAY")
                        .requestMatchers(HttpMethod.POST, "/api/devices/*/power", "/api/devices/*/channels/*",
                                "/api/scenes/*/apply", "/api/voice/command")
                        .hasAnyRole("USER", "DISPLAY")
                        .requestMatchers(HttpMethod.GET, "/api/auth/me", "/api/weather", "/api/tasks",
                                "/api/calendar/today", "/api/devices", "/api/rooms", "/api/scenes", "/api/settings",
                                "/api/stream", "/api/voice/status")
                        .hasAnyRole("USER", "DISPLAY")
                        .requestMatchers("/api/**").hasRole("USER")
                        .anyRequest().permitAll()); // the web app's static files must load before login
        return http.build();
    }
}
