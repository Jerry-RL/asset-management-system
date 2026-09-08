package com.ams;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class AmsApplication {

    public static void main(String[] args) {
        SpringApplication.run(AmsApplication.class, args);
    }
}
