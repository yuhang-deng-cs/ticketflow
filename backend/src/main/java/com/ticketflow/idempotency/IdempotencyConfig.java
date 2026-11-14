package com.ticketflow.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Picks the store with {@code ticketflow.idempotency.store}: {@code memory} (default, single node)
 * or {@code redis} (shared across instances).
 */
@Configuration
public class IdempotencyConfig {

    @Bean
    @ConditionalOnProperty(name = "ticketflow.idempotency.store", havingValue = "redis")
    IdempotencyStore redisIdempotencyStore(StringRedisTemplate redis, ObjectMapper objectMapper) {
        return new RedisIdempotencyStore(redis, objectMapper);
    }

    @Bean
    @ConditionalOnProperty(name = "ticketflow.idempotency.store", havingValue = "memory", matchIfMissing = true)
    IdempotencyStore inMemoryIdempotencyStore() {
        return new InMemoryIdempotencyStore();
    }

    /** The filter is registered here, not as a @Component, so it runs after Spring Security has set the user. */
    @Bean
    FilterRegistrationBean<IdempotencyFilter> idempotencyFilterRegistration(IdempotencyStore store,
                                                                            ObjectMapper objectMapper) {
        FilterRegistrationBean<IdempotencyFilter> registration =
                new FilterRegistrationBean<>(new IdempotencyFilter(store, objectMapper));
        registration.addUrlPatterns("/api/v1/orders", "/api/v1/orders/*");
        registration.setOrder(0);
        return registration;
    }
}
