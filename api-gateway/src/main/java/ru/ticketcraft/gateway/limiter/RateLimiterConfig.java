package ru.ticketcraft.gateway.limiter;

import java.net.InetSocketAddress;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

@Configuration
public class RateLimiterConfig {

    @Bean
    KeyResolver ipKeyResolver() {
        return exchange -> Mono.just(getClientIp(exchange));
    }

    @Bean
    @Primary
    ResilientRateLimiter resilientRateLimiter(RedisRateLimiter redisRateLimiter,

                                              @Value("${ticketcraft.gateway.rate-limiter.timeout:500ms}")
                                              Duration rateLimiterTimeout) {
        return new ResilientRateLimiter(redisRateLimiter, rateLimiterTimeout);
    }

    private String getClientIp(ServerWebExchange exchange) {
        InetSocketAddress remoteAddress = exchange.getRequest().getRemoteAddress();

        if (remoteAddress == null || remoteAddress.getAddress() == null) {
            return "unknown";
        }

        return remoteAddress.getAddress().getHostAddress();
    }

}
