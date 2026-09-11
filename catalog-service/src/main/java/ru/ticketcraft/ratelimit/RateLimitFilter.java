package ru.ticketcraft.ratelimit;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RateLimitFilter extends OncePerRequestFilter {

    static final String HEADER_RATE_LIMIT_LIMIT = "X-RateLimit-Limit";
    static final String HEADER_RATE_LIMIT_REMAINING = "X-RateLimit-Remaining";

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private static final String CATALOG_EVENTS_PATH = "/api/v1/catalog/events";

    private static final String TICKETS_PATH_PREFIX = "/api/v1/catalog/tickets/";

    private static final String RESERVE_PATH_SUFFIX = "/reserve";

    private static final long FAILURE_LOG_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(30);

    private final AtomicLong nextFailureLogNanos = new AtomicLong();

    private final RateLimitProperties properties;
    private final RedisRateLimiter rateLimiter;
    private final ClientKeyResolver clientKeyResolver;
    private final RateLimitMetrics metrics;

    public RateLimitFilter(RateLimitProperties properties, RedisRateLimiter rateLimiter,
            ClientKeyResolver clientKeyResolver, RateLimitMetrics metrics) {

        this.properties = properties;
        this.rateLimiter = rateLimiter;
        this.clientKeyResolver = clientKeyResolver;
        this.metrics = metrics;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        if (!properties.enabled()) {
            filterChain.doFilter(request, response);
            return;
        }

        RateLimitPolicy policy = resolvePolicy(request);
        if (policy == null) {
            filterChain.doFilter(request, response);
            return;
        }

        String clientKey = clientKeyResolver.resolve(request);

        RateLimitResult result;
        try {
            result = rateLimiter.acquire(policy, clientKey);
        } catch (DataAccessException ex) {
            metrics.recordError(policy);

            logRateLimiterUnavailable(policy, request, ex);

            filterChain.doFilter(request, response);
            return;
        }

        applyRateLimitHeaders(response, result);

        if (result.allowed()) {
            metrics.recordAllowed(policy);
            filterChain.doFilter(request, response);
            return;
        }

        metrics.recordRejected(policy);
        writeTooManyRequests(response, result.retryAfter());
    }

    private void logRateLimiterUnavailable(RateLimitPolicy policy, HttpServletRequest request,
            DataAccessException exception) {

        long now = System.nanoTime();
        long nextLogAt = nextFailureLogNanos.get();

        if (now < nextLogAt) {
            return;
        }

        if (!nextFailureLogNanos.compareAndSet(nextLogAt, now + FAILURE_LOG_INTERVAL_NANOS)) {
            return;
        }

        log.warn("Rate limiter unavailable; allowing request. policy={}, path={}, error={}", policy.key(),
                request.getRequestURI(), exception.getClass().getSimpleName());
    }

    private static RateLimitPolicy resolvePolicy(HttpServletRequest request) {

        String method = request.getMethod();
        String path = requestPath(request);

        if (HttpMethod.GET.matches(method)
                && (CATALOG_EVENTS_PATH.equals(path) || path.startsWith(CATALOG_EVENTS_PATH + "/"))) {
            return RateLimitPolicy.CATALOG_READ;
        }

        if (HttpMethod.POST.matches(method) && path.startsWith(TICKETS_PATH_PREFIX)
                && path.endsWith(RESERVE_PATH_SUFFIX)) {
            return RateLimitPolicy.TICKET_RESERVATION;
        }

        return null;
    }

    private static String requestPath(HttpServletRequest request) {
        String requestUri = request.getRequestURI();
        String contextPath = request.getContextPath();

        if (contextPath == null || contextPath.isEmpty()) {
            return requestUri;
        }

        return requestUri.substring(contextPath.length());
    }

    private static void applyRateLimitHeaders(HttpServletResponse response, RateLimitResult result) {

        response.setHeader(HEADER_RATE_LIMIT_LIMIT, Long.toString(result.limit()));

        response.setHeader(HEADER_RATE_LIMIT_REMAINING, Long.toString(result.remaining()));
    }

    private static void writeTooManyRequests(HttpServletResponse response, Duration retryAfter) throws IOException {

        long retryAfterSeconds = Math.max(1L, (retryAfter.toMillis() + 999L) / 1000L);

        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");

        response.getWriter().write("""
                {"type":"about:blank","title":"Rate limit exceeded","status":429,"detail":"Too many requests"}
                """);
    }
}
