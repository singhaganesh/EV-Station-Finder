package com.ganesh.finder.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Value("${app.admin.emails:singhaganesh@gmail.com}")
    private String adminEmailsStr;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> auth
                // Public read endpoints
                .requestMatchers(HttpMethod.GET, "/api/stations/nearby").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/stations/viewport").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/stations/*/detail").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/stations/search").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/stations/*/reviews").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/stations/route/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/stations/count").permitAll()
                
                // Allow OPTIONS pre-flight
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                
                // Admin destructive endpoints
                .requestMatchers("/api/import/**", "/api/stations/cleanup-duplicates").hasRole("ADMIN")
                
                // User endpoints
                .requestMatchers("/api/me/**").authenticated()
                .requestMatchers(HttpMethod.POST, "/api/stations/*/reviews").authenticated()
                
                .anyRequest().authenticated()
            )
            .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())));

        return http.build();
    }

    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            String email = jwt.getClaimAsString("email");
            List<String> adminEmails = adminEmailsStr != null 
                ? Arrays.asList(adminEmailsStr.split(",")) 
                : Collections.emptyList();
            
            List<org.springframework.security.core.GrantedAuthority> authorities = new java.util.ArrayList<>();
            if (email != null && adminEmails.contains(email.trim().toLowerCase())) {
                authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
                authorities.add(new SimpleGrantedAuthority("ROLE_USER"));
            } else {
                authorities.add(new SimpleGrantedAuthority("ROLE_USER"));
            }
            return authorities;
        });
        return converter;
    }
}
