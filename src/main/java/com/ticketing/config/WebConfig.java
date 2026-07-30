package com.ticketing.config;

import com.ticketing.security.RateLimitInterceptor;
import com.ticketing.security.IpRateLimitInterceptor;
import com.ticketing.service.RateLimiterService;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers the rate-limit interceptor against the booking hold endpoint only.
 * Keeping the path mapping here (rather than annotating the controller) makes the
 * cross-cutting concern explicit and easy to extend to other buckets later.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final RateLimiterService rateLimiterService;

    public WebConfig(RateLimiterService rateLimiterService) {
        this.rateLimiterService = rateLimiterService;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new RateLimitInterceptor(rateLimiterService))
                .addPathPatterns("/api/v1/bookings/hold");

        // Limit login attempts to 10 per minute per IP address
        registry.addInterceptor(new IpRateLimitInterceptor(rateLimiterService, "login-ip", 10, 60))
                .addPathPatterns("/api/v1/auth/login");
    }
}
