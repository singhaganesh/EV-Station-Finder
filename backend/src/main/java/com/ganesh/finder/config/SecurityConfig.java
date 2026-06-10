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
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Value("${app.admin.emails:}")
    private String adminEmailsStr;

    @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}")
    private String jwkSetUri;

    @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}")
    private String issuerUri;

    @Bean
    public JwtDecoder jwtDecoder() {
        NimbusJwtDecoder jwtDecoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri)
                .jwsAlgorithm(SignatureAlgorithm.ES256)
                .build();

        OAuth2TokenValidator<Jwt> audienceValidator = jwt -> {
            if (jwt.getAudience().contains("authenticated")) {
                return OAuth2TokenValidatorResult.success();
            }
            return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Invalid audience", null));
        };

        OAuth2TokenValidator<Jwt> withIssuer = new JwtIssuerValidator(issuerUri);
        OAuth2TokenValidator<Jwt> withTimestamp = new JwtTimestampValidator();
        OAuth2TokenValidator<Jwt> validator = new DelegatingOAuth2TokenValidator<>(withTimestamp, withIssuer, audienceValidator);

        jwtDecoder.setJwtValidator(validator);
        
        return jwtDecoder;
    }

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
            // Normalize BOTH sides (trim + lowercase) so config whitespace/casing can't
            // silently grant or deny admin.
            List<String> adminEmails = (adminEmailsStr != null && !adminEmailsStr.isBlank())
                ? Arrays.stream(adminEmailsStr.split(","))
                        .map(s -> s.trim().toLowerCase())
                        .filter(s -> !s.isEmpty())
                        .collect(java.util.stream.Collectors.toList())
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
