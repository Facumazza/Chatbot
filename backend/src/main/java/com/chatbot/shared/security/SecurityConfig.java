package com.chatbot.shared.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Dos superficies: el webhook de Meta, público y protegido por firma (Etapa 5),
 * y la API del panel, con sesión y CSRF (Etapa 8). Todo lo demás exige autenticación.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, SecurityProblemHandler problemHandler)
            throws Exception {
        http
                .csrf(csrf -> csrf.spa().ignoringRequestMatchers("/webhooks/**"))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers("/actuator/health/**", "/webhooks/**").permitAll()
                        // Solo existen con el perfil dev; en los demás responden 404.
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .anyRequest().authenticated())
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint(problemHandler)
                        .accessDeniedHandler(problemHandler));
        return http.build();
    }
}
