package com.nocountry.simulation.communitylab.infrastructure.config.redis;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

/**
 * Redis connection factory: the configured timeouts must reach Lettuce (a hand-built factory used to drop them and
 * fall back to 60s per command). No Spring context and no live Redis: the factory is only built, never started.
 *
 * <p>Derivado de: spec 010 RF-01 ({@code REDIS_TIMEOUT} aplica a comandos y conexión en todos los perfiles) + plan 010 §A.
 */
@DisplayName("RedisConfig")
class RedisConfigTest {

    private final RedisConfig config = new RedisConfig();

    @Test
    @DisplayName("Dado REDIS_TIMEOUT=2s, cuando se crea la factoría, entonces cada comando tiene ese tope y no los 60s de Lettuce")
    void commandTimeoutReachesLettuce() {
        // Cuando se crea con los valores por defecto del application.yaml
        LettuceConnectionFactory factory = (LettuceConnectionFactory) config.redisConnectionFactory(
                "redis", 6379, Duration.ofSeconds(2), Duration.ofSeconds(2));

        // Entonces un Redis colgado bloquea como mucho 2s (ingesta, flush e historial SSE)
        assertThat(factory.getClientConfiguration().getCommandTimeout()).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    @DisplayName("Dado un connect-timeout, cuando se crea la factoría, entonces la conexión usa ese tope")
    void connectTimeoutReachesLettuce() {
        LettuceConnectionFactory factory = (LettuceConnectionFactory) config.redisConnectionFactory(
                "redis", 6379, Duration.ofSeconds(2), Duration.ofMillis(1500));

        assertThat(factory.getClientConfiguration().getClientOptions()).hasValueSatisfying(options ->
                assertThat(options.getSocketOptions().getConnectTimeout()).isEqualTo(Duration.ofMillis(1500)));
    }

    @Test
    @DisplayName("Dado host y puerto, cuando se crea la factoría, entonces apunta a ese Redis")
    void hostAndPortAreKept() {
        LettuceConnectionFactory factory = (LettuceConnectionFactory) config.redisConnectionFactory(
                "redis", 6380, Duration.ofSeconds(2), Duration.ofSeconds(2));

        assertThat(factory.getStandaloneConfiguration().getHostName()).isEqualTo("redis");
        assertThat(factory.getStandaloneConfiguration().getPort()).isEqualTo(6380);
    }
}
