package com.msa.booking.payment.config;

import com.msa.booking.payment.security.BearerAuthenticationFilter;
import com.msa.booking.payment.security.AuthenticatedPaymentRateLimitFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {
    private final BearerAuthenticationFilter bearerAuthenticationFilter;
    private final AuthenticatedPaymentRateLimitFilter authenticatedPaymentRateLimitFilter;

    public SecurityConfig(
            BearerAuthenticationFilter bearerAuthenticationFilter,
            AuthenticatedPaymentRateLimitFilter authenticatedPaymentRateLimitFilter
    ) {
        this.bearerAuthenticationFilter = bearerAuthenticationFilter;
        this.authenticatedPaymentRateLimitFilter = authenticatedPaymentRateLimitFilter;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exceptionHandling -> exceptionHandling
                        .authenticationEntryPoint((request, response, authException) -> response.sendError(401))
                )
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/actuator/**").permitAll()
                        .requestMatchers("/api/v1/payments/webhooks/razorpay").permitAll()
                        .requestMatchers(HttpMethod.POST, "/booking-requests/expire").permitAll()
                        .requestMatchers("/api/v1/payments/**").authenticated()
                        .requestMatchers("/booking-requests/**").authenticated()
                        .requestMatchers("/booking-payments/**").authenticated()
                        .requestMatchers("/booking-notifications/**").authenticated()
                        .requestMatchers("/shop-orders/cancel").authenticated()
                        .anyRequest().permitAll())
                .addFilterBefore(authenticatedPaymentRateLimitFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(bearerAuthenticationFilter, AuthenticatedPaymentRateLimitFilter.class);
        return http.build();
    }
}
