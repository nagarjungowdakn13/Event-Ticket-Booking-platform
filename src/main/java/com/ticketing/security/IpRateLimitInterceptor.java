package com.ticketing.security;

import com.ticketing.service.RateLimiterService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Rate limit interceptor based on request IP address. Suitable for guarding public endpoints.
 */
public class IpRateLimitInterceptor implements HandlerInterceptor {

    private final RateLimiterService rateLimiterService;
    private final String bucket;
    private final int maxRequests;
    private final int windowSeconds;

    public IpRateLimitInterceptor(RateLimiterService rateLimiterService, String bucket, int maxRequests, int windowSeconds) {
        this.rateLimiterService = rateLimiterService;
        this.bucket = bucket;
        this.maxRequests = maxRequests;
        this.windowSeconds = windowSeconds;
    }

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request,
                             @NonNull HttpServletResponse response,
                             @NonNull Object handler) {
        String ip = request.getRemoteAddr();
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            ip = xForwardedFor.split(",")[0].trim();
        }
        rateLimiterService.checkLimitByKey(bucket, ip, maxRequests, windowSeconds);
        return true;
    }
}
