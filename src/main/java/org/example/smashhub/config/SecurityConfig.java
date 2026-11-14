package org.example.smashhub.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
// Bật @PreAuthorize trên controller. AccessDeniedException ném ra từ đây sẽ đi
// qua GlobalExceptionHandler -> 403 JSON.
@EnableMethodSecurity
public class SecurityConfig {

        private static final String[] PUBLIC_POST_ENDPOINTS = {
                        "/auth/login", "/users",
                        "/auth/introspect", "/auth/logout",
                        "/auth/refresh", "/auth/refresh-token",
                        "/auth/forgot-password",
                        "/auth/resend-otp",
                        "/auth/reset-password", "/auth/verify-account",
                        "/shipping/ghn/webhook", // [MỚI] Webhook GHN (xác thực token riêng)
                        "/shipping/fee", // [MỚI] Tính phí ship cho khách vãng lai & user
                        "/payment/vnpay/ipn", // [MỚI] VNPay IPN (nếu gọi bằng POST)
        };

        // Dữ liệu công khai xem được mà không cần đăng nhập.
        private static final String[] PUBLIC_GET_ENDPOINTS = {
                        "/auth/reset-password/validate",
                        "/products",
                        "/products/**",
                        "/brands",
                        "/brands/**",
                        "/categories",
                        "/categories/**",
                        "/locations/**", // [MỚI] Xem danh sách tỉnh/quận/phường
                        "/orders/*/tracking", // [MỚI] Cho phép tra cứu vận đơn không cần đăng nhập
                        "/payment/vnpay/ipn", // [MỚI] VNPay IPN callback
                        "/payment/vnpay/return", // [MỚI] VNPay Return URL redirect cho khách
        };

        // Cart supports both guest (X-Cart-Token) and authenticated (JWT) users.
        // Auth resolution is handled inside CartService, not at the security layer.
        private static final String[] PUBLIC_CART_ENDPOINTS = {
                        "/cart",
                        "/cart/**",
                        "/cart/items",
                        "/cart/items/**",
                        "/cart/merge",
        };

        @Autowired
        private CustomJwtDecoder customJwtDecoder;

        @Bean
        public PasswordEncoder passwordEncoder() {
                return new BCryptPasswordEncoder(10);
        }

        @Bean
        public SecurityFilterChain filterChain(HttpSecurity httpSecurity) {
                httpSecurity.authorizeHttpRequests(
                                request -> request.requestMatchers(HttpMethod.POST, PUBLIC_POST_ENDPOINTS).permitAll()
                                                .requestMatchers(HttpMethod.GET, PUBLIC_GET_ENDPOINTS).permitAll()
                                                .requestMatchers(PUBLIC_CART_ENDPOINTS).permitAll()
                                                .requestMatchers("/ws-smashhub/**").permitAll()
                                                .anyRequest().authenticated());

                httpSecurity.oauth2ResourceServer(oauth2 -> oauth2
                                .jwt(jwtConfigurer -> jwtConfigurer.decoder(customJwtDecoder)
                                                .jwtAuthenticationConverter(jwtAuthenticationConverter()))
                                .authenticationEntryPoint(new JwtAuthenticationEntryPoint()));
                httpSecurity.cors(cors -> cors.configurationSource(corsConfigurationSource()));
                httpSecurity.csrf(AbstractHttpConfigurer::disable);

                return httpSecurity.build();
        }

        @Bean
        public CorsConfigurationSource corsConfigurationSource() {
                CorsConfiguration configuration = new CorsConfiguration();
                configuration.setAllowedOriginPatterns(List.of("*"));
                configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
                configuration.setAllowedHeaders(List.of("*"));
                configuration.setExposedHeaders(List.of("X-Cart-Token", "Authorization"));
                configuration.setAllowCredentials(true);

                UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
                source.registerCorsConfiguration("/**", configuration);
                return source;
        }

        @Bean
        JwtAuthenticationConverter jwtAuthenticationConverter() {
                JwtGrantedAuthoritiesConverter jwtGrantedAuthoritiesConverter = new JwtGrantedAuthoritiesConverter();
                // Token đã chứa scope "ROLE_ADMIN", nên không thêm prefix SCOPE_ ->
                // hasRole('ADMIN') khớp "ROLE_ADMIN".
                jwtGrantedAuthoritiesConverter.setAuthorityPrefix("");
                JwtAuthenticationConverter jwtAuthenticationConverter = new JwtAuthenticationConverter();
                jwtAuthenticationConverter.setJwtGrantedAuthoritiesConverter(jwtGrantedAuthoritiesConverter);

                return jwtAuthenticationConverter;
        }

}
