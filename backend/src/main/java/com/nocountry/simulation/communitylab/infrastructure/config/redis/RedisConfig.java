package com.nocountry.simulation.communitylab.infrastructure.config.redis;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;

@Configuration
public class RedisConfig {
    // Why the timeouts are wired here: a hand-built factory ignores spring.data.redis.timeout, so Lettuce fell back to
    // 60s per command and a hung Redis froze ingest, flush and the SSE history for a whole minute (spec 010 RF-01).
    @Bean
    public RedisConnectionFactory redisConnectionFactory(@Value("${spring.data.redis.host:localhost}") String host,
                                                         @Value("${spring.data.redis.port:6379}") int port,
                                                         @Value("${spring.data.redis.timeout:2000ms}") Duration commandTimeout,
                                                         @Value("${spring.data.redis.connect-timeout:2000ms}") Duration connectTimeout) {
        LettuceClientConfiguration clientConfig = LettuceClientConfiguration.builder()
                .commandTimeout(commandTimeout)
                .clientOptions(ClientOptions.builder()
                        .socketOptions(SocketOptions.builder().connectTimeout(connectTimeout).build())
                        .build())
                .build();
        return new LettuceConnectionFactory(new RedisStandaloneConfiguration(host, port), clientConfig);
    }

    @Bean
    public RedisTemplate<String, String> redisTemplate(RedisConnectionFactory redisConnectionFactory) {
        // Create Redis Template and added connection
        RedisTemplate<String, String> template = new RedisTemplate<>();
        template.setConnectionFactory(redisConnectionFactory);

        // Configuration serialization
        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        template.setKeySerializer(stringSerializer);
        template.setValueSerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);
        template.setHashValueSerializer(stringSerializer);
        template.afterPropertiesSet();

        return template;
    }
}
