package com.nocountry.simulation.communitylab.infrastructure.config.bucket;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class RateLimitAiConfig {

    @Bean
    public Bucket rateLimitAi(){

        Bandwidth limit = Bandwidth.builder()
                .capacity(3)
                .refillIntervally(3, Duration.ofSeconds(60))
                .build();

        return  Bucket.builder()
                .addLimit(limit)
                .build();
    }
}
