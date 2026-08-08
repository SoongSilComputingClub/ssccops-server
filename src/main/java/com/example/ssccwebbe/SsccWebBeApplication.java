package com.example.ssccwebbe;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class SsccWebBeApplication {

    public static void main(String[] args) {
        SpringApplication.run(SsccWebBeApplication.class, args);
    }
}
