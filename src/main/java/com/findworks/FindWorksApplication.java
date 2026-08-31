package com.findworks;

import com.findworks.platform.config.FindWorksProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(FindWorksProperties.class)
public class FindWorksApplication {

    public static void main(String[] args) {
        SpringApplication.run(FindWorksApplication.class, args);
    }
}
