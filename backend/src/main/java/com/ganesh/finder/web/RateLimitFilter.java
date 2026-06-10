package com.ganesh.finder.web;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-client-IP rate limiting backed by Bucket4j. Three tiers:
 *   - review POSTs   (tightest, anti review-bombing)
 *   - other writes   (POST/PUT/DELETE, incl. /api/me/** and /api/import/**)
 *   - public reads   (GET /api/stations/**)
 * Keyed by IP because this servlet filter runs before Spring Security populates
 * the authentication, so the JWT subject is not yet available here.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    @Value("${app.ratelimit.enabled:true}")
    private boolean enabled;

    @Value("${app.ratelimit.public-per-minute:60}")
    private int publicPerMinute;

    @Value("${app.ratelimit.write-per-minute:20}")
    private int writePerMinute;

    @Value("${app.ratelimit.review-per-minute:5}")
    private int reviewPerMinute;

    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Only throttle the API surface.
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        if (!enabled || HttpMethod.OPTIONS.matches(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }

        String category;
        int limit;
        String path = request.getRequestURI();
        boolean isWrite = !HttpMethod.GET.matches(request.getMethod());

        if (isWrite && path.matches("/api/stations/\\d+/reviews")) {
            category = "review";
            limit = reviewPerMinute;
        } else if (isWrite) {
            category = "write";
            limit = writePerMinute;
        } else {
            category = "public";
            limit = publicPerMinute;
        }

        String key = category + ":" + clientIp(request);
        Bucket bucket = buckets.computeIfAbsent(key, k -> newBucket(limit));

        if (bucket.tryConsume(1)) {
            chain.doFilter(request, response);
        } else {
            response.setStatus(429); // Too Many Requests
            response.setContentType("application/json");
            response.getWriter().write(
                    "{\"success\":false,\"message\":\"Rate limit exceeded. Please slow down.\",\"data\":null}");
        }
    }

    private Bucket newBucket(int perMinute) {
        Bandwidth limit = Bandwidth.builder()
                .capacity(perMinute)
                .refillGreedy(perMinute, Duration.ofMinutes(1))
                .build();
        return Bucket.builder().addLimit(limit).build();
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
