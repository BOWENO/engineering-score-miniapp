package com.acme.performance;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestClient;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableScheduling
@EnableAsync
public class EngineeringScoreApplication {
    public static void main(String[] args) {
        SpringApplication.run(EngineeringScoreApplication.class, args);
    }

    @Bean
    RestClient restClient() {
        return RestClient.create();
    }
}
