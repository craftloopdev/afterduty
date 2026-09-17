package com.afterduty;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableAsync
@EnableScheduling
public class AfterDutyApplication {

    public static void main(String[] args) {
        SpringApplication.run(AfterDutyApplication.class, args);
    }

    @Bean
    public java.time.Clock systemClock() { return java.time.Clock.systemUTC(); }
}
