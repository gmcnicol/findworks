package com.findworks;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class FindWorksApplication {

    public static void main(String[] args) {
        SpringApplication.run(FindWorksApplication.class, args);
    }
}
