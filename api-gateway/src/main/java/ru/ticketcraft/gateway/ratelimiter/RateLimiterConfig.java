package ru.ticketcraft.gateway.ratelimiter;

import java.net.InetSocketAddress;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ServerWebExchange;

import io.micrometer.core.instrument.MeterRegistry;
import reactor.core.publisher.Mono;

@Configuration
@EnableConfigurationProperties(GatewayRateLimiterProperties.class)
public class RateLimiterConfig {

    @Bean
    KeyResolver ipKeyResolver() {
        return exchange -> Mono.just(getClientIp(exchange));
    }

    @Bean
    @Primary
    KeyResolver userKeyResolver() {
        return exchange -> exchange.getPrincipal()
                                   .ofType(JwtAuthenticationToken.class)
                                   .flatMap(authentication -> Mono.justOrEmpty(authentication.getToken().getSubject()))
                                   .filter(subject -> !subject.isBlank())
                                   .map(subject -> "user:" + subject);
    }

    @Bean
    @Primary
    ResilientRateLimiter resilientRateLimiter(RedisRateLimiter redisRateLimiter,
                                              GatewayRateLimiterProperties properties,
                                              MeterRegistry meterRegistry) {

        return new ResilientRateLimiter(redisRateLimiter, properties.timeout(), properties.warnInterval(),
                meterRegistry);
    }

    private String getClientIp(ServerWebExchange exchange) {
        InetSocketAddress remoteAddress = exchange.getRequest().getRemoteAddress();

        if (remoteAddress == null || remoteAddress.getAddress() == null) {
            return "unknown";
        }

        return remoteAddress.getAddress().getHostAddress();
    }

}
