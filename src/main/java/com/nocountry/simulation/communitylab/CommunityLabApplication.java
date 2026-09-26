package com.nocountry.simulation.communitylab;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class CommunityLabApplication {

    public static void main(String[] args) {
        SpringApplication.run(CommunityLabApplication.class, args);
    }

}
